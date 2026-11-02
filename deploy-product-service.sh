#!/usr/bin/env bash
# =============================================================================
# deploy-product-service.sh - one-command deploy of product-service to ECS Fargate
#
# A thin wrapper around deploy-service.sh (same folder) that supplies product-service defaults.
#
# Required (no defaults on purpose, so you cannot deploy to the wrong cluster/database by accident):
#   CLUSTER_NAME  RDS_ENDPOINT  DB_USERNAME  DB_PASSWORD
# Optional:
#   AWS_PROFILE AWS_REGION CREATE_DB=1 ALLOW_NEW_CLUSTER=1 NO_CACHE=1 SKIP_BUILD=1 IMAGE_TAG JWT_SECRET EXTRA_ENV
#   EVENT_PUBLISHER=kafka KAFKA_BOOTSTRAP=PRIVATE_IP:9092 KAFKA_SG_ID     (Kafka is OFF by default)
#   SHARE_JWT_SECRET=0   (default 1: reuse auth-service's JWT secret if .deploy-auth-service/jwt-secret.txt exists)
#
# Run in Git Bash or WSL from the repo root.  See docs/DEPLOYMENT.md.
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

[[ -f "$HERE/deploy-service.sh" ]] || die "deploy-service.sh must be in the same folder as this script (the repo root)"
[[ -f "$HERE/product-service/pom.xml" ]] || die "product-service/pom.xml not found next to this script. Run from the repo root."

: "${CLUSTER_NAME:?Export CLUSTER_NAME (see: aws ecs list-clusters)}"
: "${RDS_ENDPOINT:?Export RDS_ENDPOINT (the RDS host name, no port)}"
: "${DB_USERNAME:?Export DB_USERNAME (the RDS master user)}"
: "${DB_PASSWORD:?Export DB_PASSWORD}"

export SERVICE_NAME="${SERVICE_NAME:-product-service}"
export SERVICE_DIR="${SERVICE_DIR:-$HERE/product-service}"
export CONTAINER_PORT="${CONTAINER_PORT:-8082}"
export CONTEXT_PATH="${CONTEXT_PATH:-/api}"
export SPRING_PROFILE="${SPRING_PROFILE:-dev}"
export DB_NAME="${DB_NAME:-product_database}"
export CREATE_DB="${CREATE_DB:-0}"
export EVENT_PUBLISHER="${EVENT_PUBLISHER:-logging}"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_PROFILE="${AWS_PROFILE:-}"
export CLUSTER_NAME RDS_ENDPOINT DB_USERNAME DB_PASSWORD

# product-service carries the JWT libraries. If it ever verifies tokens issued by auth-service, both must share ONE
# secret, so reuse auth-service's secret when we have it. Harmless if product-service does not verify tokens.
if [[ -z "${JWT_SECRET:-}" && "${SHARE_JWT_SECRET:-1}" == "1" && -f "$HERE/.deploy-auth-service/jwt-secret.txt" ]]; then
  JWT_SECRET="$(tr -d '\r\n ' < "$HERE/.deploy-auth-service/jwt-secret.txt")"
  export JWT_SECRET
  log "Reusing auth-service's JWT secret"
fi

if [[ "$EVENT_PUBLISHER" == "kafka" ]]; then
  [[ -z "${KAFKA_BOOTSTRAP:-}" ]] && die "EVENT_PUBLISHER=kafka needs KAFKA_BOOTSTRAP (private IP:9092; run: bash kafka-ec2.sh up)"
  if [[ -z "${KAFKA_SG_ID:-}" ]]; then
    KAFKA_SG_ID="$(aws ${AWS_PROFILE:+--profile "$AWS_PROFILE"} --region "$AWS_REGION" ec2 describe-security-groups \
                  --filters Name=group-name,Values=kafka-sg --query "SecurityGroups[0].GroupId" --output text 2>/dev/null || true)"
    [[ "$KAFKA_SG_ID" == "None" ]] && KAFKA_SG_ID=""
  fi
  export KAFKA_BOOTSTRAP KAFKA_SG_ID
fi

cat >&2 <<BANNER

------------------------------------------------------------
 Deploying ${SERVICE_NAME}
   cluster        : ${CLUSTER_NAME}
   database       : ${DB_NAME} @ ${RDS_ENDPOINT}   (create it now: CREATE_DB=${CREATE_DB})
   port / context : ${CONTAINER_PORT} ${CONTEXT_PATH}
   events         : ${EVENT_PUBLISHER} ${KAFKA_BOOTSTRAP:+(broker ${KAFKA_BOOTSTRAP})}
------------------------------------------------------------

BANNER

exec bash "$HERE/deploy-service.sh"
