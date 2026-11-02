#!/usr/bin/env bash
# =============================================================================
# deploy-inventory-service.sh - one-command deploy of inventory-service to ECS Fargate
#
# A thin wrapper around deploy-service.sh that supplies inventory-service defaults and wires up
# the INTER-SERVICE settings (product-service URL, internal API key, Kafka security group).
#
# Required (no defaults on purpose, so you cannot deploy to the wrong cluster/database by accident):
#   CLUSTER_NAME  RDS_ENDPOINT  DB_USERNAME  DB_PASSWORD
# Optional:
#   AWS_PROFILE AWS_REGION CREATE_DB=1 NO_CACHE=1 SKIP_BUILD=1 IMAGE_TAG
#   EVENT_PUBLISHER=kafka KAFKA_BOOTSTRAP=PRIVATE_IP:9092 KAFKA_SG_ID
#   ENABLE_PRODUCT_VALIDATION=1 PRODUCT_SERVICE_URL=http://DNS/api INTERNAL_API_KEY EXTRA_ENV
#
# Run in Git Bash or WSL from the repo root (next to deploy-service.sh).
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$HERE/deploy-service.sh" ]] || die "deploy-service.sh must be in the same folder as this script"

# ---- required inputs -------------------------------------------------------
: "${CLUSTER_NAME:?Export CLUSTER_NAME (see: aws ecs list-clusters). Use the same cluster as your other services.}"
: "${RDS_ENDPOINT:?Export RDS_ENDPOINT (the RDS host name, no port)}"
: "${DB_USERNAME:?Export DB_USERNAME (the RDS master user)}"
: "${DB_PASSWORD:?Export DB_PASSWORD}"

# ---- inventory-service defaults ---------------------------------------------
export SERVICE_NAME="${SERVICE_NAME:-inventory-service}"
export SERVICE_DIR="${SERVICE_DIR:-$HERE/inventory-service}"
export CONTAINER_PORT="${CONTAINER_PORT:-8083}"
export CONTEXT_PATH="${CONTEXT_PATH:-/api}"
export SPRING_PROFILE="${SPRING_PROFILE:-dev}"
export DB_NAME="${DB_NAME:-inventory_database}"
export CREATE_DB="${CREATE_DB:-0}"
export EVENT_PUBLISHER="${EVENT_PUBLISHER:-logging}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_PROFILE="${AWS_PROFILE:-}"
export CLUSTER_NAME RDS_ENDPOINT DB_USERNAME DB_PASSWORD

AWS_ARGS=(--region "$AWS_REGION")
[[ -n "$AWS_PROFILE" ]] && AWS_ARGS+=(--profile "$AWS_PROFILE")
aws_() { aws "${AWS_ARGS[@]}" "$@"; }

WORK_DIR=".deploy-${SERVICE_NAME}"
mkdir -p "$WORK_DIR"

# ---- >>> INBOUND protection: shared API key (generated once, reused) ---------------------------
KEY_FILE="$WORK_DIR/internal-api-key.txt"
if [[ -z "${INTERNAL_API_KEY:-}" ]]; then
  if [[ -f "$KEY_FILE" ]]; then
    INTERNAL_API_KEY="$(tr -d '\r\n ' < "$KEY_FILE")"
  else
    if command -v openssl >/dev/null 2>&1; then
      INTERNAL_API_KEY="$(openssl rand -hex 24)"
    else
      INTERNAL_API_KEY="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    fi
    ( umask 077; printf '%s' "$INTERNAL_API_KEY" > "$KEY_FILE" )
    log "Generated internal API key, saved to $KEY_FILE (keep it out of Git)"
  fi
fi

# ---- >>> OUTBOUND call Inventory -> Product: discover product-service's ALB ----------------------
PRODUCT_SERVICE_URL="${PRODUCT_SERVICE_URL:-}"
if [[ -z "$PRODUCT_SERVICE_URL" ]]; then
  dns="$(aws_ elbv2 describe-load-balancers --names product-service-alb \
        --query "LoadBalancers[0].DNSName" --output text 2>/dev/null || true)"
  if [[ -n "$dns" && "$dns" != "None" ]]; then
    PRODUCT_SERVICE_URL="http://$dns/api"
    log "Discovered product-service at $PRODUCT_SERVICE_URL"
  fi
fi
ENABLE_PRODUCT_VALIDATION="${ENABLE_PRODUCT_VALIDATION:-0}"
if [[ "$ENABLE_PRODUCT_VALIDATION" == "1" && -z "$PRODUCT_SERVICE_URL" ]]; then
  die "ENABLE_PRODUCT_VALIDATION=1 but product-service ALB was not found. Export PRODUCT_SERVICE_URL=http://DNS/api"
fi

EXTRA=("SECURITY_INTERNAL_API_KEY=$INTERNAL_API_KEY")
[[ -n "$PRODUCT_SERVICE_URL" ]] && EXTRA+=("CLIENTS_PRODUCT_SERVICE_BASE_URL=$PRODUCT_SERVICE_URL")
if [[ "$ENABLE_PRODUCT_VALIDATION" == "1" ]]; then
  EXTRA+=("CLIENTS_PRODUCT_SERVICE_ENABLED=true")
else
  EXTRA+=("CLIENTS_PRODUCT_SERVICE_ENABLED=false")
fi
[[ -n "${EXTRA_ENV:-}" ]] && EXTRA+=("$EXTRA_ENV")
EXTRA_ENV="$(IFS=','; echo "${EXTRA[*]}")"
export EXTRA_ENV

# ---- Kafka ------------------------------------------------------------------------------------
if [[ "$EVENT_PUBLISHER" == "kafka" ]]; then
  [[ -z "${KAFKA_BOOTSTRAP:-}" ]] && die "EVENT_PUBLISHER=kafka needs KAFKA_BOOTSTRAP (private IP:9092; run: bash kafka-ec2.sh up)"
  if [[ -z "${KAFKA_SG_ID:-}" ]]; then
    KAFKA_SG_ID="$(aws_ ec2 describe-security-groups --filters Name=group-name,Values=kafka-sg \
                  --query "SecurityGroups[0].GroupId" --output text 2>/dev/null || true)"
    [[ "$KAFKA_SG_ID" == "None" ]] && KAFKA_SG_ID=""
  fi
  export KAFKA_BOOTSTRAP KAFKA_SG_ID
fi

cat >&2 <<EOF

------------------------------------------------------------
 Deploying ${SERVICE_NAME}
   cluster            : ${CLUSTER_NAME}
   database           : ${DB_NAME} @ ${RDS_ENDPOINT}   (create it now: CREATE_DB=${CREATE_DB})
   port / context     : ${CONTAINER_PORT} ${CONTEXT_PATH}
   events             : ${EVENT_PUBLISHER} ${KAFKA_BOOTSTRAP:+(broker ${KAFKA_BOOTSTRAP})}
   product validation : ${ENABLE_PRODUCT_VALIDATION} ${PRODUCT_SERVICE_URL:+(${PRODUCT_SERVICE_URL})}
   internal API key   : saved in ${KEY_FILE}
------------------------------------------------------------

EOF

exec bash "$HERE/deploy-service.sh"
