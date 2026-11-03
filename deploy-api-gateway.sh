#!/usr/bin/env bash
# =============================================================================
# deploy-api-gateway.sh - one-command deploy of api-gateway to ECS Fargate (NO database)
#
# A thin wrapper around deploy-service.sh (v4.3 or newer: needs NO_DB support). It finds the other services'
# load balancers, reads the JWT secret and the internal API keys from the folders the other deploy scripts created,
# and passes them to the gateway as environment variables. Secrets are never printed.
#
# Required:  CLUSTER_NAME
# Optional:  AWS_PROFILE AWS_REGION ALLOW_NEW_CLUSTER=1 NO_CACHE=1 SKIP_BUILD=1 IMAGE_TAG
#   JWT_SECRET            default: read from .deploy-auth-service/jwt-secret.txt (MUST equal auth-service's secret)
#   AUTH_URL PRODUCT_URL ORDER_URL NOTIFICATION_URL INVENTORY_URL   http://DNS (found automatically by ALB name:
#                         auth-service-alb, product-service-alb, order-service-alb, notification-service-alb)
#   PRODUCT_PUBLIC=1      product browsing without login
#   EXPOSE_INVENTORY=1    expose /api/inventory/** (internal service; off by default)
#   CORS_ORIGINS          one allowed browser origin or pattern, e.g. https://shop.example.com (no commas)
#   EXTRA_ENV="A=1,B=2"
#
# Run in Git Bash or WSL from the repo root, the same folder you ran the other deploy scripts from.
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$HERE/deploy-service.sh" ]] || die "deploy-service.sh must be in the same folder as this script"
grep -q 'NO_DB' "$HERE/deploy-service.sh" || die "deploy-service.sh is too old (no NO_DB support). Replace it with v4.3 from this package."

: "${CLUSTER_NAME:?Export CLUSTER_NAME, for example ecommerce-api-gateway-cluster}"

export SERVICE_NAME="${SERVICE_NAME:-api-gateway}"
export SERVICE_DIR="${SERVICE_DIR:-$HERE/api-gateway}"
export CONTAINER_PORT="${CONTAINER_PORT:-8080}"
export CONTEXT_PATH="${CONTEXT_PATH:-/api}"
export SPRING_PROFILE="${SPRING_PROFILE:-dev}"
export NO_DB=1
export EVENT_PUBLISHER=logging
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_PROFILE="${AWS_PROFILE:-}"
export CLUSTER_NAME

AWS_ARGS=(--region "$AWS_REGION")
[[ -n "$AWS_PROFILE" ]] && AWS_ARGS+=(--profile "$AWS_PROFILE")
aws_() { aws "${AWS_ARGS[@]}" "$@"; }

# ---- JWT secret: must be IDENTICAL to auth-service's ----
if [[ -z "${JWT_SECRET:-}" ]]; then
  f=".deploy-auth-service/jwt-secret.txt"
  [[ -f "$f" ]] || die "JWT secret not found ($f). Run this from the folder you deployed auth-service from, or export JWT_SECRET (the exact value auth-service uses). A different secret makes every token invalid (HTTP 401)."
  JWT_SECRET="$(tr -d '\r\n ' < "$f")"
  log "Using auth-service's JWT secret from $f"
fi
export JWT_SECRET

# ---- find the other services ----
alb_url() {   # $1 = ALB name
  local dns
  dns="$(aws_ elbv2 describe-load-balancers --names "$1" --query "LoadBalancers[0].DNSName" --output text 2>/dev/null || true)"
  if [[ -n "$dns" && "$dns" != "None" ]]; then echo "http://$dns"; fi
}
read_key() {  # $1 = file
  [[ -f "$1" ]] && tr -d '\r\n ' < "$1" || true
}

AUTH_URL="${AUTH_URL:-$(alb_url auth-service-alb)}"
PRODUCT_URL="${PRODUCT_URL:-$(alb_url product-service-alb)}"
ORDER_URL="${ORDER_URL:-$(alb_url order-service-alb)}"
NOTIFICATION_URL="${NOTIFICATION_URL:-$(alb_url notification-service-alb)}"
INVENTORY_URL="${INVENTORY_URL:-}"
[[ "${EXPOSE_INVENTORY:-0}" == "1" && -z "$INVENTORY_URL" ]] && INVENTORY_URL="$(alb_url inventory-service-alb)"

[[ -n "$AUTH_URL" ]] || die "auth-service ALB 'auth-service-alb' not found in $AWS_REGION. Export AUTH_URL=http://DNS (the gateway needs at least auth-service)."

EXTRA=("GATEWAY_ROUTES_AUTH_URL=$AUTH_URL")
SUMMARY="auth"
[[ -n "$PRODUCT_URL" ]] && { EXTRA+=("GATEWAY_ROUTES_PRODUCT_URL=$PRODUCT_URL"); SUMMARY+=", product"; }
if [[ -n "$ORDER_URL" ]]; then
  EXTRA+=("GATEWAY_ROUTES_ORDER_URL=$ORDER_URL")
  k="${ORDER_API_KEY:-$(read_key .deploy-order-service/internal-api-key.txt)}"
  [[ -n "$k" ]] && EXTRA+=("GATEWAY_ROUTES_ORDER_KEY=$k") || log "WARNING: order-service key not found (.deploy-order-service/internal-api-key.txt); orders will get HTTP 401 from order-service"
  SUMMARY+=", order"
fi
if [[ -n "$NOTIFICATION_URL" ]]; then
  EXTRA+=("GATEWAY_ROUTES_NOTIFICATION_URL=$NOTIFICATION_URL" "GATEWAY_ROUTES_NOTIFICATION_ENABLED=true")
  k="${NOTIFICATION_API_KEY:-$(read_key .deploy-notification-service/internal-api-key.txt)}"
  [[ -n "$k" ]] && EXTRA+=("GATEWAY_ROUTES_NOTIFICATION_KEY=$k") || log "WARNING: notification-service key not found"
  SUMMARY+=", notification"
fi
if [[ "${EXPOSE_INVENTORY:-0}" == "1" ]]; then
  [[ -n "$INVENTORY_URL" ]] || die "EXPOSE_INVENTORY=1 but inventory-service ALB not found. Export INVENTORY_URL=http://DNS"
  EXTRA+=("GATEWAY_ROUTES_INVENTORY_URL=$INVENTORY_URL" "GATEWAY_ROUTES_INVENTORY_ENABLED=true")
  k="${INVENTORY_API_KEY:-$(read_key .deploy-inventory-service/internal-api-key.txt)}"
  [[ -n "$k" ]] && EXTRA+=("GATEWAY_ROUTES_INVENTORY_KEY=$k")
  SUMMARY+=", inventory"
fi
[[ "${PRODUCT_PUBLIC:-0}" == "1" ]] && EXTRA+=("GATEWAY_ROUTES_PRODUCT_AUTHENTICATED=false")
[[ -n "${CORS_ORIGINS:-}" ]] && EXTRA+=("GATEWAY_CORS_ORIGINS=$CORS_ORIGINS")
[[ -n "${EXTRA_ENV:-}" ]] && EXTRA+=("$EXTRA_ENV")
EXTRA_ENV="$(IFS=','; echo "${EXTRA[*]}")"
export EXTRA_ENV

cat >&2 <<EOT

------------------------------------------------------------
 Deploying ${SERVICE_NAME}  (no database)
   cluster        : ${CLUSTER_NAME}
   port / context : ${CONTAINER_PORT} ${CONTEXT_PATH}
   routes         : ${SUMMARY}
   product public : ${PRODUCT_PUBLIC:-0}      inventory exposed: ${EXPOSE_INVENTORY:-0}
   JWT secret     : (from auth-service, not printed)
------------------------------------------------------------

EOT

exec bash "$HERE/deploy-service.sh"
