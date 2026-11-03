#!/usr/bin/env bash
# =============================================================================
# deploy-order-service.sh - one-command deploy of order-service to ECS Fargate
#
# A thin wrapper around deploy-service.sh that supplies order-service defaults and wires up the
# INTER-SERVICE settings: inventory-service URL + its API key (mandatory), product-service URL (optional),
# this service's own inbound API key, and the Kafka security group.
#
# Required (no defaults on purpose, so you cannot deploy to the wrong cluster/database by accident):
#   CLUSTER_NAME  RDS_ENDPOINT  DB_USERNAME  DB_PASSWORD
# Deploy inventory-service FIRST: order-service needs its load balancer and its API key.
# Optional:
#   AWS_PROFILE AWS_REGION CREATE_DB=1 ALLOW_NEW_CLUSTER=1 NO_CACHE=1 SKIP_BUILD=1 IMAGE_TAG
#   INVENTORY_SERVICE_URL=http://DNS/api   INVENTORY_API_KEY=...   (normally auto-discovered, see below)
#   ENABLE_PRODUCT_LOOKUP=1 PRODUCT_SERVICE_URL=http://DNS/api     (price/sku/name from product-service)
#   INTERNAL_API_KEY (this service's own inbound key)  SKIP_PREFLIGHT=1  EXTRA_ENV
#   EVENT_PUBLISHER=kafka KAFKA_BOOTSTRAP=PRIVATE_IP:9092 KAFKA_SG_ID
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
: "${CLUSTER_NAME:?Export CLUSTER_NAME (see: aws ecs list-clusters), for example ecommerce-order-service-cluster}"
: "${RDS_ENDPOINT:?Export RDS_ENDPOINT (the RDS host name, no port)}"
: "${DB_USERNAME:?Export DB_USERNAME (the RDS master user)}"
: "${DB_PASSWORD:?Export DB_PASSWORD}"

# ---- order-service defaults -------------------------------------------------
export SERVICE_NAME="${SERVICE_NAME:-order-service}"
export SERVICE_DIR="${SERVICE_DIR:-$HERE/order-service}"
export CONTAINER_PORT="${CONTAINER_PORT:-8084}"
export CONTEXT_PATH="${CONTEXT_PATH:-/api}"
export SPRING_PROFILE="${SPRING_PROFILE:-dev}"
export DB_NAME="${DB_NAME:-order_database}"
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

new_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24
  else head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
}

# ---- >>> INBOUND protection: this service's own API key (generated once, reused) --------------
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

# ---- >>> OUTBOUND call Order -> Inventory: URL (discovered) and API key (read from inventory's deploy folder) ---
INVENTORY_SERVICE_URL="${INVENTORY_SERVICE_URL:-}"
if [[ -z "$INVENTORY_SERVICE_URL" ]]; then
  dns="$(aws_ elbv2 describe-load-balancers --names inventory-service-alb \
        --query "LoadBalancers[0].DNSName" --output text 2>/dev/null || true)"
  if [[ -n "$dns" && "$dns" != "None" ]]; then
    INVENTORY_SERVICE_URL="http://$dns/api"
    log "Discovered inventory-service at $INVENTORY_SERVICE_URL"
  fi
fi
[[ -n "$INVENTORY_SERVICE_URL" ]] || die "inventory-service load balancer 'inventory-service-alb' was not found in $AWS_REGION. Deploy inventory-service first, or export INVENTORY_SERVICE_URL=http://DNS/api"

INVENTORY_KEY_FILE="${INVENTORY_KEY_FILE:-.deploy-inventory-service/internal-api-key.txt}"
if [[ -z "${INVENTORY_API_KEY:-}" ]]; then
  if [[ -f "$INVENTORY_KEY_FILE" ]]; then
    INVENTORY_API_KEY="$(tr -d '\r\n ' < "$INVENTORY_KEY_FILE")"
    log "Using inventory-service's API key from $INVENTORY_KEY_FILE"
  else
    die "inventory-service's API key not found at $INVENTORY_KEY_FILE. Run this script from the same folder you deployed inventory-service from, or export INVENTORY_API_KEY=... (without it every call to inventory-service gets HTTP 401 and all orders stay PENDING)"
  fi
fi

# ---- pre-flight: does inventory-service accept that key? (401/403 = wrong key, 404 = key accepted) ------------
if [[ "${SKIP_PREFLIGHT:-0}" != "1" ]] && command -v curl >/dev/null 2>&1; then
  code="$(curl -s -m 15 -o /dev/null -w '%{http_code}' -H "X-Internal-Api-Key: ${INVENTORY_API_KEY}" \
          "${INVENTORY_SERVICE_URL}/inventory/reservations/preflight-probe" 2>/dev/null || true)"
  case "$code" in
    404|200) log "Pre-flight OK: inventory-service accepted the API key (HTTP $code)";;
    401|403) die "Pre-flight FAILED: inventory-service rejected the API key (HTTP $code). Use the key from the inventory deployment (.deploy-inventory-service/internal-api-key.txt)";;
    000|"")  log "WARNING: pre-flight could not reach $INVENTORY_SERVICE_URL (network/DNS). Continuing; check inventory-service health afterwards.";;
    *)       log "WARNING: pre-flight got HTTP $code from inventory-service. Continuing.";;
  esac
fi

# ---- >>> OUTBOUND call Order -> Product (optional): price, sku and name come from the catalog -------------------
PRODUCT_SERVICE_URL="${PRODUCT_SERVICE_URL:-}"
if [[ -z "$PRODUCT_SERVICE_URL" ]]; then
  dns="$(aws_ elbv2 describe-load-balancers --names product-service-alb \
        --query "LoadBalancers[0].DNSName" --output text 2>/dev/null || true)"
  if [[ -n "$dns" && "$dns" != "None" ]]; then
    PRODUCT_SERVICE_URL="http://$dns/api"
    log "Discovered product-service at $PRODUCT_SERVICE_URL"
  fi
fi
ENABLE_PRODUCT_LOOKUP="${ENABLE_PRODUCT_LOOKUP:-0}"
if [[ "$ENABLE_PRODUCT_LOOKUP" == "1" && -z "$PRODUCT_SERVICE_URL" ]]; then
  die "ENABLE_PRODUCT_LOOKUP=1 but product-service ALB was not found. Export PRODUCT_SERVICE_URL=http://DNS/api"
fi

# ---- environment for the container (names map to Spring properties: CLIENTS_X_Y -> clients.x-y) -----------------
EXTRA=("SECURITY_INTERNAL_API_KEY=$INTERNAL_API_KEY"
       "CLIENTS_INVENTORY_SERVICE_ENABLED=true"
       "CLIENTS_INVENTORY_SERVICE_BASE_URL=$INVENTORY_SERVICE_URL"
       "CLIENTS_INVENTORY_SERVICE_API_KEY=$INVENTORY_API_KEY")
[[ -n "$PRODUCT_SERVICE_URL" ]] && EXTRA+=("CLIENTS_PRODUCT_SERVICE_BASE_URL=$PRODUCT_SERVICE_URL")
if [[ "$ENABLE_PRODUCT_LOOKUP" == "1" ]]; then
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

# secrets are never printed: only where they are stored
cat >&2 <<EOT

------------------------------------------------------------
 Deploying ${SERVICE_NAME}
   cluster            : ${CLUSTER_NAME}
   database           : ${DB_NAME} @ ${RDS_ENDPOINT}   (create it now: CREATE_DB=${CREATE_DB})
   port / context     : ${CONTAINER_PORT} ${CONTEXT_PATH}
   events             : ${EVENT_PUBLISHER} ${KAFKA_BOOTSTRAP:+(broker ${KAFKA_BOOTSTRAP})}
   inventory-service  : ${INVENTORY_SERVICE_URL}   (API key: from ${INVENTORY_KEY_FILE:-INVENTORY_API_KEY})
   product lookup     : ${ENABLE_PRODUCT_LOOKUP} ${PRODUCT_SERVICE_URL:+(${PRODUCT_SERVICE_URL})}
   this service's key : saved in ${KEY_FILE}
------------------------------------------------------------

EOT

exec bash "$HERE/deploy-service.sh"
