#!/usr/bin/env bash
# =============================================================================
# deploy-notification-service.sh - one-command deploy of notification-service to ECS Fargate
#
# A thin wrapper around deploy-service.sh (v4.3 or newer) that supplies notification-service defaults.
# By default the service runs with the LOG channel only (no Kafka, no e-mail): every notification is stored in
# its own database and logged. Turn the real inputs/outputs on with the optional settings below.
#
# Required (no defaults on purpose, so you cannot deploy to the wrong cluster/database by accident):
#   CLUSTER_NAME  RDS_ENDPOINT  DB_USERNAME  DB_PASSWORD
# Optional:
#   AWS_PROFILE AWS_REGION CREATE_DB=1 ALLOW_NEW_CLUSTER=1 NO_CACHE=1 SKIP_BUILD=1 IMAGE_TAG
#   INTERNAL_API_KEY (this service's own inbound key; generated once into .deploy-notification-service/)
#   KAFKA_BOOTSTRAP=PRIVATE_IP:9092 [KAFKA_SG_ID]    consume order events (run: bash kafka-ec2.sh up)
#   E-mail (all three needed together):
#     SMTP_HOST  SMTP_PORT (default 587)  SMTP_USERNAME  SMTP_PASSWORD
#     EMAIL_FROM  EMAIL_DEFAULT_RECIPIENT
#     (values must not contain commas; Amazon SES SMTP works but SES sandbox needs verified sender AND recipient)
#   EXTRA_ENV="A=1,B=2"
#
# Run in Git Bash or WSL from the repo root (next to deploy-service.sh).
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$HERE/deploy-service.sh" ]] || die "deploy-service.sh must be in the same folder as this script"

: "${CLUSTER_NAME:?Export CLUSTER_NAME (see: aws ecs list-clusters), for example ecommerce-notification-service-cluster}"
: "${RDS_ENDPOINT:?Export RDS_ENDPOINT (the RDS host name, no port)}"
: "${DB_USERNAME:?Export DB_USERNAME (the RDS master user)}"
: "${DB_PASSWORD:?Export DB_PASSWORD}"

export SERVICE_NAME="${SERVICE_NAME:-notification-service}"
export SERVICE_DIR="${SERVICE_DIR:-$HERE/notification-service}"
export CONTAINER_PORT="${CONTAINER_PORT:-8085}"
export CONTEXT_PATH="${CONTEXT_PATH:-/api}"
export SPRING_PROFILE="${SPRING_PROFILE:-dev}"
export DB_NAME="${DB_NAME:-notification_database}"
export CREATE_DB="${CREATE_DB:-0}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_PROFILE="${AWS_PROFILE:-}"
export CLUSTER_NAME RDS_ENDPOINT DB_USERNAME DB_PASSWORD

AWS_ARGS=(--region "$AWS_REGION")
[[ -n "$AWS_PROFILE" ]] && AWS_ARGS+=(--profile "$AWS_PROFILE")
aws_() { aws "${AWS_ARGS[@]}" "$@"; }

WORK_DIR=".deploy-${SERVICE_NAME}"
mkdir -p "$WORK_DIR"

new_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
}

# ---- inbound protection: this service's own API key (generated once, reused) ----
KEY_FILE="$WORK_DIR/internal-api-key.txt"
if [[ -z "${INTERNAL_API_KEY:-}" ]]; then
  if [[ -f "$KEY_FILE" ]]; then
    INTERNAL_API_KEY="$(tr -d '\r\n ' < "$KEY_FILE")"
  else
    INTERNAL_API_KEY="$(new_secret)"
    ( umask 077; printf '%s' "$INTERNAL_API_KEY" > "$KEY_FILE" )
    log "Generated this service's API key, saved to $KEY_FILE (keep it out of Git)"
  fi
fi

EXTRA=("SECURITY_INTERNAL_API_KEY=$INTERNAL_API_KEY")

# ---- Kafka consumer (optional) ----
KAFKA_MODE="off (events only via POST /api/notifications/events)"
if [[ -n "${KAFKA_BOOTSTRAP:-}" ]]; then
  [[ "$KAFKA_BOOTSTRAP" == localhost* || "$KAFKA_BOOTSTRAP" == 127.* ]] && die "KAFKA_BOOTSTRAP must be the broker's PRIVATE IP, not localhost (localhost is the container itself on ECS)"
  if [[ -z "${KAFKA_SG_ID:-}" ]]; then
    KAFKA_SG_ID="$(aws_ ec2 describe-security-groups --filters Name=group-name,Values=kafka-sg \
                  --query "SecurityGroups[0].GroupId" --output text 2>/dev/null || true)"
    [[ "$KAFKA_SG_ID" == "None" ]] && KAFKA_SG_ID=""
  fi
  # EVENT_PUBLISHER=kafka makes the engine set SPRING_KAFKA_BOOTSTRAP_SERVERS and open port 9092 from the task SG.
  export EVENT_PUBLISHER=kafka KAFKA_BOOTSTRAP KAFKA_SG_ID
  EXTRA+=("NOTIFICATIONS_KAFKA_ENABLED=true")
  KAFKA_MODE="consuming order events from ${KAFKA_BOOTSTRAP}"
else
  export EVENT_PUBLISHER=logging
fi

# ---- e-mail (optional) ----
EMAIL_MODE="off (LOG channel only)"
if [[ -n "${SMTP_HOST:-}" ]]; then
  : "${SMTP_USERNAME:?SMTP_HOST is set, so SMTP_USERNAME is required}"
  : "${SMTP_PASSWORD:?SMTP_HOST is set, so SMTP_PASSWORD is required}"
  : "${EMAIL_FROM:?SMTP_HOST is set, so EMAIL_FROM is required (the verified sender address)}"
  for v in SMTP_HOST SMTP_USERNAME SMTP_PASSWORD EMAIL_FROM EMAIL_DEFAULT_RECIPIENT; do
    [[ "${!v:-}" == *,* || "${!v:-}" == *\"* || "${!v:-}" == *\\* ]] && die "$v contains a comma, quote or backslash, which the EXTRA_ENV mechanism cannot carry. Use a value without them."
  done
  EXTRA+=("NOTIFICATIONS_EMAIL_ENABLED=true"
          "NOTIFICATIONS_EMAIL_FROM=$EMAIL_FROM"
          "NOTIFICATIONS_EMAIL_DEFAULT_RECIPIENT=${EMAIL_DEFAULT_RECIPIENT:-}"
          "SPRING_MAIL_HOST=$SMTP_HOST"
          "SPRING_MAIL_PORT=${SMTP_PORT:-587}"
          "SPRING_MAIL_USERNAME=$SMTP_USERNAME"
          "SPRING_MAIL_PASSWORD=$SMTP_PASSWORD"
          "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true"
          "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true")
  EMAIL_MODE="on via ${SMTP_HOST}:${SMTP_PORT:-587} from ${EMAIL_FROM}"
fi

[[ -n "${EXTRA_ENV:-}" ]] && EXTRA+=("$EXTRA_ENV")
EXTRA_ENV="$(IFS=','; echo "${EXTRA[*]}")"
export EXTRA_ENV

cat >&2 <<EOT

------------------------------------------------------------
 Deploying ${SERVICE_NAME}
   cluster            : ${CLUSTER_NAME}
   database           : ${DB_NAME} @ ${RDS_ENDPOINT}   (create it now: CREATE_DB=${CREATE_DB})
   port / context     : ${CONTAINER_PORT} ${CONTEXT_PATH}
   kafka              : ${KAFKA_MODE}
   e-mail             : ${EMAIL_MODE}
   this service's key : saved in ${KEY_FILE}
------------------------------------------------------------

EOT

exec bash "$HERE/deploy-service.sh"
