#!/usr/bin/env bash
# =============================================================================
# deploy-service.sh (v4) - Deploy a Spring Boot microservice to AWS ECS Fargate
#   ECR + ECS Fargate + ALB + CloudWatch, RDS PostgreSQL, optional Kafka (EC2/MSK)
#
# Idempotent and safe to re-run. Every run:
#   * reuses existing AWS resources,
#   * builds and pushes a NEW uniquely-tagged image,
#   * registers a NEW task definition revision,
#   * pins the ECS service to that exact revision (no stale-revision trap).
#
# v3 changes:
#   * Database creation rewritten: uses local psql OR a Docker psql (no PATH issues),
#     auto-detects the RDS security group, opens 5432 to YOUR IP only, no pipe/pipefail bug,
#     and fails with a clear explanation instead of a vague error.
#   * RDS security group is auto-detected from RDS_ENDPOINT, so tasks can always reach RDS.
#   * Refuses to create a new ECS cluster unless ALLOW_NEW_CLUSTER=1 (wrong-cluster mistake).
#   * JWT secret is generated once and reused (tokens survive redeploys).
#   * task-def.json (contains secrets) is deleted when the script exits.
#   * Name-length and DB-name validation.
#
# v4 changes:
#   * EXTRA_ENV="NAME=VALUE,NAME2=VALUE2" injects extra environment variables into the task
#     (inter-service URLs, API keys, feature flags). Values must not contain commas, quotes or backslashes.
#   * Fully backward compatible with v3: it can replace your existing deploy-service.sh for every service.
#
# Run in Git Bash or WSL.  See KAFKA-INTEGRATION-GUIDE.md.
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1   # stop Git Bash from rewriting /paths and file:// args

# ------------------------------ CONFIGURATION --------------------------------
: "${SERVICE_NAME:=YOUR_SERVICE_NAME}"        # e.g. product-service (<= 25 chars)
: "${SERVICE_DIR:=./${SERVICE_NAME}}"         # folder with Dockerfile + pom.xml
: "${CONTAINER_PORT:=8081}"                   # MUST equal server.port in the app
: "${CONTEXT_PATH:=/api}"                     # server.servlet.context-path ("" if none)
: "${SPRING_PROFILE:=dev}"
: "${AWS_REGION:=us-east-1}"
: "${AWS_PROFILE:=}"                          # blank = default profile
: "${CLUSTER_NAME:=ecommerce-cluster}"        # EXPORT THIS EVERY TIME (see guide)
: "${ALLOW_NEW_CLUSTER:=0}"                   # 1 = allowed to create the cluster if missing
: "${TASK_CPU:=512}"                          # 256 was too small: 2+ minute startup
: "${TASK_MEMORY:=1024}"
: "${DESIRED_COUNT:=1}"
: "${IMAGE_TAG:=$(date +%Y%m%d-%H%M%S)}"
: "${SKIP_BUILD:=0}"                          # 1 = reuse image already in ECR (rollback)
: "${NO_CACHE:=0}"                            # 1 = docker build --no-cache

# Database / secrets (Phase 1: plain env vars. Phase 2: Secrets Manager)
: "${RDS_ENDPOINT:=}"
: "${DB_NAME:=}"
: "${DB_USERNAME:=}"
: "${DB_PASSWORD:=}"
: "${JWT_SECRET:=}"
: "${CREATE_DB:=0}"                           # 1 = create the database if it does not exist
: "${RDS_ALLOW_MY_IP:=1}"                     # with CREATE_DB=1: open RDS 5432 to your public IP /32
: "${RDS_SG_ID:=}"                            # optional: auto-detected from RDS_ENDPOINT if blank
: "${PSQL_IMAGE:=postgres:16-alpine}"         # used when psql is not installed locally

# Messaging
: "${EVENT_PUBLISHER:=logging}"               # logging | kafka
: "${KAFKA_BOOTSTRAP:=}"                      # e.g. 172.31.10.20:9092 (private IP!)
: "${KAFKA_SG_ID:=}"                          # optional: opens 9092 from the task SG

# Extra environment variables for the container
: "${EXTRA_ENV:=}"                          # "NAME=VALUE,NAME2=VALUE2"

HEALTH_PATH="${CONTEXT_PATH}/actuator/health"
ALB_NAME="${SERVICE_NAME}-alb"
TG_NAME="${SERVICE_NAME}-tg"
LOG_GROUP="/ecs/${SERVICE_NAME}"
EXEC_ROLE_NAME="ecsTaskExecutionRole"
WORK_DIR=".deploy-${SERVICE_NAME}"

# ------------------------------- HELPERS -------------------------------------
log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

AWS_ARGS=(--region "$AWS_REGION")
[[ -n "$AWS_PROFILE" ]] && AWS_ARGS+=(--profile "$AWS_PROFILE")
aws_() { aws "${AWS_ARGS[@]}" "$@"; }

cleanup() { rm -f "${WORK_DIR}/task-def.json" 2>/dev/null || true; }   # contains DB password + JWT secret
trap cleanup EXIT

# Add an ingress rule. Ignores ONLY "already exists"; any other failure stops the script.
add_ingress() {   # add_ingress <sg-id> <port> <source-sg-id | cidr>
  local out rc=0
  if [[ "$3" == sg-* ]]; then
    out=$(aws_ ec2 authorize-security-group-ingress --group-id "$1" --protocol tcp \
          --port "$2" --source-group "$3" 2>&1) || rc=$?
  else
    out=$(aws_ ec2 authorize-security-group-ingress --group-id "$1" --protocol tcp \
          --port "$2" --cidr "$3" 2>&1) || rc=$?
  fi
  if [[ $rc -ne 0 && "$out" != *InvalidPermission.Duplicate* ]]; then
    die "Could not add ingress port $2 from $3 to $1: $out"
  fi
}

ensure_sg() {   # ensure_sg <name> <description> -> prints group id
  local id
  id=$(aws_ ec2 describe-security-groups \
        --filters "Name=group-name,Values=$1" "Name=vpc-id,Values=$VPC_ID" \
        --query "SecurityGroups[0].GroupId" --output text)
  if [[ "$id" == "None" ]]; then
    id=$(aws_ ec2 create-security-group --group-name "$1" --description "$2" \
          --vpc-id "$VPC_ID" --query GroupId --output text)
  fi
  echo "$id"
}

# Run one SQL statement against RDS. Uses local psql if installed, otherwise Docker.
run_psql() {   # run_psql <database> <sql>
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" PGCONNECT_TIMEOUT=10 psql -h "$RDS_ENDPOINT" -p 5432 \
      -U "$DB_USERNAME" -d "$1" -v ON_ERROR_STOP=1 -tA -c "$2"
  else
    docker run --rm -e PGPASSWORD="$DB_PASSWORD" -e PGCONNECT_TIMEOUT=10 "$PSQL_IMAGE" \
      psql -h "$RDS_ENDPOINT" -p 5432 -U "$DB_USERNAME" -d "$1" -v ON_ERROR_STOP=1 -tA -c "$2"
  fi
}

ensure_database() {
  [[ "$DB_NAME" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] \
    || die "DB_NAME '$DB_NAME' must contain only letters, digits and underscores (and not start with a digit)"

  # Open RDS to this machine's public IP (/32 only) so the create can connect
  if [[ "$RDS_ALLOW_MY_IP" == "1" && -n "$RDS_SG_ID" ]]; then
    local myip
    myip=$(curl -s -m 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)
    if [[ -n "$myip" ]]; then
      log "Allowing ${myip}/32 -> RDS 5432 on $RDS_SG_ID (your machine only)"
      add_ingress "$RDS_SG_ID" 5432 "${myip}/32"
    else
      log "WARNING: could not detect your public IP; assuming RDS already allows it"
    fi
  fi

  local exists rc=0
  exists=$(run_psql postgres "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" 2>&1) || rc=$?
  if [[ $rc -ne 0 ]]; then
    echo "$exists" >&2
    if [[ "$exists" == *"password authentication failed"* ]]; then
      die "RDS rejected the username/password. DB_USERNAME must be the RDS MASTER user (check: aws rds describe-db-instances --query 'DBInstances[*].[DBInstanceIdentifier,MasterUsername]')"
    fi
    die "Could not connect to RDS at ${RDS_ENDPOINT}:5432. Check: (1) RDS 'Publicly accessible' = Yes (${RDS_PUBLIC:-unknown}), (2) RDS security group allows your IP on 5432, (3) VPN/firewall not blocking 5432. Or create it by hand: CREATE DATABASE ${DB_NAME};  then re-run with CREATE_DB=0"
  fi
  if grep -qx '1' <<< "$exists"; then
    log "Database '${DB_NAME}' already exists"
    return 0
  fi
  run_psql postgres "CREATE DATABASE \"${DB_NAME}\"" >/dev/null \
    || die "CREATE DATABASE failed. Create it manually and re-run with CREATE_DB=0"
  log "Created database '${DB_NAME}'"
}

diagnose() {
  log "Deployment did not stabilise. Diagnostics:"
  aws_ ecs describe-services --cluster "$CLUSTER_NAME" --services "$SERVICE_NAME" \
    --query "services[0].events[0:8].message" --output text || true
  local stopped
  stopped=$(aws_ ecs list-tasks --cluster "$CLUSTER_NAME" --service-name "$SERVICE_NAME" \
    --desired-status STOPPED --query "taskArns[0]" --output text || true)
  if [[ -n "$stopped" && "$stopped" != "None" ]]; then
    aws_ ecs describe-tasks --cluster "$CLUSTER_NAME" --tasks "$stopped" \
      --query "tasks[0].{reason:stoppedReason,exit:containers[0].exitCode,taskDef:taskDefinitionArn}" || true
  fi
  [[ -n "${TG_ARN:-}" ]] && aws_ elbv2 describe-target-health --target-group-arn "$TG_ARN" || true
  log "Logs : aws logs tail ${LOG_GROUP} --since 15m ${AWS_PROFILE:+--profile $AWS_PROFILE} --region $AWS_REGION"
  log "Check: Target.Timeout -> task SG rule for port ${CONTAINER_PORT} from ALB SG | exit 1 -> Flyway/DB errors in logs"
  log "       DB errors      -> database '${DB_NAME}' exists? task SG allowed into the RDS SG on 5432?"
  log "       Kafka hang     -> KAFKA_BOOTSTRAP private IP + Kafka SG 9092 from task SG"
}

# ------------------------------ 0. PRE-FLIGHT --------------------------------
log "0/11 Pre-flight checks"
[[ "$SERVICE_NAME" == "YOUR_SERVICE_NAME" ]] && die "Set SERVICE_NAME first."
[[ ${#SERVICE_NAME} -gt 25 ]] && die "SERVICE_NAME must be <= 25 chars (ALB/target-group names max 32 incl. suffix)"
[[ "$SERVICE_NAME" =~ ^[a-z0-9-]+$ ]] || die "SERVICE_NAME may contain only lowercase letters, digits and hyphens"
[[ -z "$RDS_ENDPOINT" || -z "$DB_NAME" || -z "$DB_USERNAME" || -z "$DB_PASSWORD" ]] \
  && die "Set RDS_ENDPOINT, DB_NAME, DB_USERNAME and DB_PASSWORD."
command -v aws    >/dev/null || die "aws CLI not found"
command -v docker >/dev/null || die "docker not found"
docker info >/dev/null 2>&1  || die "Docker daemon not running (start Docker Desktop, wait for 'Engine running')"
[[ -f "$SERVICE_DIR/Dockerfile" ]] || die "No Dockerfile in $SERVICE_DIR"
mkdir -p "$WORK_DIR"

# Values are written into JSON; quotes or backslashes would corrupt it.
for v in DB_USERNAME DB_PASSWORD JWT_SECRET KAFKA_BOOTSTRAP; do
  val="${!v}"
  [[ "$val" == *\"* || "$val" == *\\* ]] && die "$v must not contain double quotes or backslashes"
done

# JWT secret: generate ONCE, then reuse so issued tokens survive redeploys
JWT_FILE="$WORK_DIR/jwt-secret.txt"
if [[ -z "$JWT_SECRET" ]]; then
  if [[ -f "$JWT_FILE" ]]; then
    JWT_SECRET=$(cat "$JWT_FILE")
    log "Reusing JWT secret from $JWT_FILE"
  else
    command -v openssl >/dev/null || die "Set JWT_SECRET (>= 32 random chars) or install openssl"
    JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')
    ( umask 077; printf '%s' "$JWT_SECRET" > "$JWT_FILE" )
    log "Generated a JWT secret and saved it to $JWT_FILE (keep it out of Git)"
  fi
fi

# Extra env vars: EXTRA_ENV="NAME=VALUE,NAME2=VALUE2"  (no commas, quotes or backslashes inside values)
EXTRA_ENV_JSON=""
if [[ -n "$EXTRA_ENV" ]]; then
  IFS=',' read -ra EXTRA_PAIRS <<< "$EXTRA_ENV"
  for pair in "${EXTRA_PAIRS[@]}"; do
    [[ "$pair" == *=* ]] || die "EXTRA_ENV entry '$pair' must look like NAME=VALUE"
    ename="${pair%%=*}"
    evalue="${pair#*=}"
    [[ "$ename" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die "EXTRA_ENV name '$ename' is invalid"
    [[ "$evalue" == *\"* || "$evalue" == *\\* ]] && die "EXTRA_ENV value for $ename must not contain quotes or backslashes"
    EXTRA_ENV_JSON+=$',\n        { "name": "'"$ename"'", "value": "'"$evalue"'" }'
  done
fi

case "$EVENT_PUBLISHER" in
  logging) ;;
  kafka)
    [[ -z "$KAFKA_BOOTSTRAP" ]] && die "EVENT_PUBLISHER=kafka needs KAFKA_BOOTSTRAP (private IP:9092)"
    [[ "$KAFKA_BOOTSTRAP" == localhost* || "$KAFKA_BOOTSTRAP" == 127.* ]] \
      && die "KAFKA_BOOTSTRAP points to localhost, which is the container itself on ECS. Use the broker's private IP."
    ;;
  *) die "EVENT_PUBLISHER must be 'logging' or 'kafka'" ;;
esac

ACCOUNT_ID=$(aws_ sts get-caller-identity --query Account --output text) || die "AWS credentials not working"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE_URI="${REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}"
DB_URL="jdbc:postgresql://${RDS_ENDPOINT}:5432/${DB_NAME}"
log "Account=$ACCOUNT_ID Region=$AWS_REGION Cluster=$CLUSTER_NAME Service=$SERVICE_NAME Tag=$IMAGE_TAG Events=$EVENT_PUBLISHER"

# ---------------------- 1. RDS DISCOVERY + DATABASE ---------------------------
log "1/11 RDS instance lookup and database"
RDS_PUBLIC=""
RDS_INFO=$(aws_ rds describe-db-instances \
  --query "DBInstances[?Endpoint.Address=='${RDS_ENDPOINT}'].[VpcSecurityGroups[0].VpcSecurityGroupId,PubliclyAccessible,DBInstanceIdentifier]" \
  --output text 2>/dev/null || true)
if [[ -n "$RDS_INFO" ]]; then
  read -r FOUND_RDS_SG RDS_PUBLIC FOUND_RDS_ID <<< "$RDS_INFO"
  [[ -z "$RDS_SG_ID" ]] && RDS_SG_ID="$FOUND_RDS_SG"
  log "RDS instance=$FOUND_RDS_ID  securityGroup=$RDS_SG_ID  publiclyAccessible=$RDS_PUBLIC"
else
  log "WARNING: no RDS instance matches endpoint '$RDS_ENDPOINT' in $AWS_REGION. Task->RDS rule will be skipped unless RDS_SG_ID is set."
fi

if [[ "$CREATE_DB" == "1" ]]; then
  ensure_database
else
  log "Skipping DB creation (CREATE_DB=0). Make sure '$DB_NAME' exists on the RDS instance!"
fi

# ------------------------------ 2. CLUSTER ------------------------------------
log "2/11 ECS cluster: $CLUSTER_NAME"
if [[ "$(aws_ ecs describe-clusters --clusters "$CLUSTER_NAME" \
      --query "clusters[?status=='ACTIVE']|length(@)" --output text)" == "0" ]]; then
  if [[ "$ALLOW_NEW_CLUSTER" != "1" ]]; then
    echo "Existing clusters:" >&2
    aws_ ecs list-clusters --query "clusterArns[*]" --output text >&2 || true
    die "Cluster '$CLUSTER_NAME' does not exist. Export the correct CLUSTER_NAME, or ALLOW_NEW_CLUSTER=1 to create it."
  fi
  aws_ ecs create-cluster --cluster-name "$CLUSTER_NAME" >/dev/null
fi

# --------------------------- 3. TASK EXECUTION ROLE ---------------------------
log "3/11 IAM role: $EXEC_ROLE_NAME"
if ! aws_ iam get-role --role-name "$EXEC_ROLE_NAME" >/dev/null 2>&1; then
  cat > "$WORK_DIR/trust-policy.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
EOF
  aws_ iam create-role --role-name "$EXEC_ROLE_NAME" \
    --assume-role-policy-document "file://$WORK_DIR/trust-policy.json" >/dev/null
  aws_ iam attach-role-policy --role-name "$EXEC_ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  sleep 10   # IAM propagation
fi
EXEC_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${EXEC_ROLE_NAME}"

# ------------------------------ 4. NETWORK ------------------------------------
log "4/11 Default VPC and subnets"
VPC_ID=$(aws_ ec2 describe-vpcs --filters Name=isDefault,Values=true \
          --query "Vpcs[0].VpcId" --output text)
[[ "$VPC_ID" == "None" ]] && die "No default VPC in $AWS_REGION"
read -r SUBNET_1 SUBNET_2 <<< "$(aws_ ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" "Name=default-for-az,Values=true" \
  --query "Subnets[0:2].SubnetId" --output text)"
[[ -z "${SUBNET_2:-}" ]] && die "Need two subnets in different AZs"

# ------------------------------ 5. SECURITY GROUPS ----------------------------
log "5/11 Security groups"
ALB_SG=$(ensure_sg "${SERVICE_NAME}-alb-sg" "ALB for ${SERVICE_NAME}")
TASK_SG=$(ensure_sg "${SERVICE_NAME}-task-sg" "ECS tasks for ${SERVICE_NAME}")
add_ingress "$ALB_SG"  80 0.0.0.0/0
add_ingress "$TASK_SG" "$CONTAINER_PORT" "$ALB_SG"          # ALB -> task (fixes Target.Timeout)
if [[ -n "$RDS_SG_ID" ]]; then
  add_ingress "$RDS_SG_ID" 5432 "$TASK_SG"                  # task -> RDS
else
  log "WARNING: RDS security group unknown; make sure it allows the task SG ($TASK_SG) on 5432"
fi
[[ -n "$KAFKA_SG_ID" ]] && add_ingress "$KAFKA_SG_ID" 9092 "$TASK_SG"

# ------------------------------ 6. ECR + BUILD + PUSH -------------------------
log "6/11 ECR repository + image"
aws_ ecr describe-repositories --repository-names "$SERVICE_NAME" >/dev/null 2>&1 \
  || aws_ ecr create-repository --repository-name "$SERVICE_NAME" >/dev/null
if [[ "$SKIP_BUILD" != "1" ]]; then
  aws_ ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
  BUILD_ARGS=(-t "${SERVICE_NAME}:${IMAGE_TAG}")
  [[ "$NO_CACHE" == "1" ]] && BUILD_ARGS+=(--no-cache)
  # cd + "." : Docker Desktop is a native Windows program and cannot read Git Bash paths like /c/Users/...
  ( cd "$SERVICE_DIR" && docker build "${BUILD_ARGS[@]}" . )
  docker tag "${SERVICE_NAME}:${IMAGE_TAG}" "$IMAGE_URI"
  docker push "$IMAGE_URI"
else
  log "SKIP_BUILD=1 -> using existing image $IMAGE_URI"
fi

# ------------------------------ 7. LOG GROUP ----------------------------------
log "7/11 CloudWatch log group: $LOG_GROUP"
aws_ logs create-log-group --log-group-name "$LOG_GROUP" >/dev/null 2>&1 || true

# ------------------------------ 8. ALB + TARGET GROUP + LISTENER --------------
log "8/11 Load balancer, target group, listener"
ALB_ARN=$(aws_ elbv2 describe-load-balancers --names "$ALB_NAME" \
          --query "LoadBalancers[0].LoadBalancerArn" --output text 2>/dev/null || echo None)
if [[ "$ALB_ARN" == "None" || -z "$ALB_ARN" ]]; then
  ALB_ARN=$(aws_ elbv2 create-load-balancer --name "$ALB_NAME" \
    --subnets "$SUBNET_1" "$SUBNET_2" --security-groups "$ALB_SG" \
    --scheme internet-facing --type application \
    --query "LoadBalancers[0].LoadBalancerArn" --output text)
fi
ALB_DNS=$(aws_ elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" \
          --query "LoadBalancers[0].DNSName" --output text)

TG_ARN=$(aws_ elbv2 describe-target-groups --names "$TG_NAME" \
         --query "TargetGroups[0].TargetGroupArn" --output text 2>/dev/null || echo None)
if [[ "$TG_ARN" == "None" || -z "$TG_ARN" ]]; then
  TG_ARN=$(aws_ elbv2 create-target-group --name "$TG_NAME" --protocol HTTP \
    --port "$CONTAINER_PORT" --vpc-id "$VPC_ID" --target-type ip \
    --health-check-path "$HEALTH_PATH" --health-check-interval-seconds 30 \
    --health-check-timeout-seconds 5 --healthy-threshold-count 2 \
    --unhealthy-threshold-count 5 --matcher HttpCode=200 \
    --query "TargetGroups[0].TargetGroupArn" --output text)
else
  aws_ elbv2 modify-target-group --target-group-arn "$TG_ARN" \
    --health-check-path "$HEALTH_PATH" >/dev/null
fi
aws_ elbv2 modify-target-group-attributes --target-group-arn "$TG_ARN" \
  --attributes Key=deregistration_delay.timeout_seconds,Value=30 >/dev/null

if [[ "$(aws_ elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" \
        --query "Listeners|length(@)" --output text)" == "0" ]]; then
  aws_ elbv2 create-listener --load-balancer-arn "$ALB_ARN" --protocol HTTP --port 80 \
    --default-actions "Type=forward,TargetGroupArn=$TG_ARN" >/dev/null
fi

# ------------------------------ 9. TASK DEFINITION ----------------------------
log "9/11 Registering task definition"
# Windows tools (openssl, aws, cat) can leave a carriage return on a value. A raw CR inside a JSON string
# makes the whole task definition invalid ("Invalid JSON received"), so strip CR/control chars first.
for _v in SERVICE_NAME TASK_CPU TASK_MEMORY EXEC_ROLE_ARN IMAGE_URI CONTAINER_PORT SPRING_PROFILE DB_URL \
          DB_USERNAME DB_PASSWORD JWT_SECRET EVENT_PUBLISHER KAFKA_BOOTSTRAP EXTRA_ENV_JSON LOG_GROUP \
          AWS_REGION HEALTH_PATH; do
  [[ -n "${!_v:-}" ]] && printf -v "$_v" '%s' "$(printf '%s' "${!_v}" | tr -d '\r\000-\010\013\014\016-\037' )"
done
cat > "$WORK_DIR/task-def.json" <<EOF
{
  "family": "${SERVICE_NAME}",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "${TASK_CPU}",
  "memory": "${TASK_MEMORY}",
  "executionRoleArn": "${EXEC_ROLE_ARN}",
  "containerDefinitions": [
    {
      "name": "${SERVICE_NAME}",
      "image": "${IMAGE_URI}",
      "essential": true,
      "portMappings": [{ "containerPort": ${CONTAINER_PORT}, "protocol": "tcp" }],
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE",         "value": "${SPRING_PROFILE}" },
        { "name": "SPRING_DATASOURCE_URL",          "value": "${DB_URL}" },
        { "name": "SPRING_DATASOURCE_USERNAME",     "value": "${DB_USERNAME}" },
        { "name": "SPRING_DATASOURCE_PASSWORD",     "value": "${DB_PASSWORD}" },
        { "name": "JWT_SECRET",                     "value": "${JWT_SECRET}" },
        { "name": "APP_EVENTS_PUBLISHER",           "value": "${EVENT_PUBLISHER}" },
        { "name": "SPRING_KAFKA_BOOTSTRAP_SERVERS", "value": "${KAFKA_BOOTSTRAP:-localhost:9092}" }${EXTRA_ENV_JSON}
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "${LOG_GROUP}",
          "awslogs-region": "${AWS_REGION}",
          "awslogs-stream-prefix": "ecs"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "wget -q --spider http://localhost:${CONTAINER_PORT}${HEALTH_PATH} || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 180
      }
    }
  ]
}
EOF
if grep -qP '[\x00-\x08\x0b-\x1f]' "$WORK_DIR/task-def.json" 2>/dev/null; then
  die "task-def.json still contains control characters; check values in $WORK_DIR"
fi
TASK_DEF_ARN=$(aws_ ecs register-task-definition \
  --cli-input-json "file://$WORK_DIR/task-def.json" \
  --query "taskDefinition.taskDefinitionArn" --output text)
log "Registered: $TASK_DEF_ARN"
cleanup   # remove the file holding secrets as soon as it has been registered

# ------------------------------ 10. ECS SERVICE -------------------------------
log "10/11 Creating/updating ECS service (pinned to the revision above)"
SVC_STATUS=$(aws_ ecs describe-services --cluster "$CLUSTER_NAME" --services "$SERVICE_NAME" \
             --query "services[0].status" --output text 2>/dev/null || echo None)
if [[ "$SVC_STATUS" == "ACTIVE" ]]; then
  aws_ ecs update-service --cluster "$CLUSTER_NAME" --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" --desired-count "$DESIRED_COUNT" \
    --health-check-grace-period-seconds 240 --force-new-deployment >/dev/null
else
  aws_ ecs create-service --cluster "$CLUSTER_NAME" --service-name "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" --desired-count "$DESIRED_COUNT" --launch-type FARGATE \
    --health-check-grace-period-seconds 240 \
    --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_1,$SUBNET_2],securityGroups=[$TASK_SG],assignPublicIp=ENABLED}" \
    --load-balancers "targetGroupArn=$TG_ARN,containerName=$SERVICE_NAME,containerPort=$CONTAINER_PORT" \
    >/dev/null
fi

# ------------------------------ 11. WAIT + VERIFY -----------------------------
log "11/11 Waiting for the service to become stable (up to ~10 min; JVM start is slow)"
if ! aws_ ecs wait services-stable --cluster "$CLUSTER_NAME" --services "$SERVICE_NAME"; then
  diagnose; exit 1
fi

URL="http://${ALB_DNS}${HEALTH_PATH}"
log "Health check: $URL"
code=000
for i in $(seq 1 20); do
  code=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$URL" || true)
  [[ "$code" == "200" ]] && break
  echo "   attempt $i: HTTP $code - retrying in 15s" >&2; sleep 15
done
[[ "$code" == "200" ]] || { diagnose; die "Health endpoint never returned 200"; }

cat <<EOF

============================================================
 DEPLOYED : ${SERVICE_NAME}   (cluster: ${CLUSTER_NAME})
 Base URL : http://${ALB_DNS}${CONTEXT_PATH}/
 Health   : ${URL}  -> HTTP 200
 Database : ${DB_NAME} @ ${RDS_ENDPOINT}
 Events   : ${EVENT_PUBLISHER}${KAFKA_BOOTSTRAP:+  (broker ${KAFKA_BOOTSTRAP})}
 Image    : ${IMAGE_URI}
 TaskDef  : ${TASK_DEF_ARN}
 Rollback : SKIP_BUILD=1 IMAGE_TAG=<previous-tag> bash deploy-service.sh
 Logs     : aws logs tail ${LOG_GROUP} --follow ${AWS_PROFILE:+--profile $AWS_PROFILE} --region ${AWS_REGION}
============================================================
EOF
