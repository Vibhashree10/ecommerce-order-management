#!/usr/bin/env bash
# =============================================================================
# kafka-ec2.sh - Single-node Kafka (KRaft, Docker) on EC2 for hands-on learning
#
# Usage:  ./kafka-ec2.sh <command> [arg]
#   up                create SG, key pair, instance; print the bootstrap address
#   status            instance state, IPs, docker status
#   allow SG_ID       allow another service's task SG to reach Kafka (port 9092)
#   allow-my-ip       re-open SSH (22) to your current public IP
#   topics            list topics
#   consume TOPIC     read messages from a topic (20s window)
#   ssh               open a shell on the broker
#   stop | start      stop/start the instance (stops billing for compute)
#   destroy           terminate instance, delete SG and key pair
#
# Requires: AWS CLI, ssh (Git Bash has it). Run in Git Bash or WSL.
# =============================================================================
set -euo pipefail
export MSYS_NO_PATHCONV=1

: "${AWS_REGION:=us-east-1}"
: "${AWS_PROFILE:=}"
: "${CLUSTER_NAME:=ecommerce-cluster}"          # cluster where your app service runs
: "${APP_SERVICE:=product-service}"             # service whose task SG may reach Kafka
: "${TASK_SG:=}"                                # optional: skip lookup
: "${KAFKA_NAME:=kafka-learning}"
: "${INSTANCE_TYPE:=t3.medium}"                 # t3.small is too tight for Kafka
: "${KEY_NAME:=kafka-key}"
: "${KEY_FILE:=./kafka-key.pem}"
WORK_DIR=".kafka-ec2"

log() { echo "==> $*" >&2; }
die() { echo "ERROR: $*" >&2; exit 1; }

AWS_ARGS=(--region "$AWS_REGION")
[[ -n "$AWS_PROFILE" ]] && AWS_ARGS+=(--profile "$AWS_PROFILE")
aws_() { aws "${AWS_ARGS[@]}" "$@"; }

add_ingress() {   # add_ingress <sg-id> <port> <source-sg-id | cidr>
  local out rc=0
  if [[ "$3" == sg-* ]]; then
    out=$(aws_ ec2 authorize-security-group-ingress --group-id "$1" --protocol tcp --port "$2" --source-group "$3" 2>&1) || rc=$?
  else
    out=$(aws_ ec2 authorize-security-group-ingress --group-id "$1" --protocol tcp --port "$2" --cidr "$3" 2>&1) || rc=$?
  fi
  if [[ $rc -ne 0 && "$out" != *InvalidPermission.Duplicate* ]]; then
    die "Could not add ingress port $2 from $3 to $1: $out"
  fi
}

vpc_id()      { aws_ ec2 describe-vpcs --filters Name=isDefault,Values=true --query "Vpcs[0].VpcId" --output text; }
kafka_sg()    { aws_ ec2 describe-security-groups --filters "Name=group-name,Values=kafka-sg" --query "SecurityGroups[0].GroupId" --output text; }
instance_id() {
  aws_ ec2 describe-instances \
    --filters "Name=tag:Name,Values=$KAFKA_NAME" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query "Reservations[0].Instances[0].InstanceId" --output text
}
inst_field()  { aws_ ec2 describe-instances --instance-ids "$1" --query "Reservations[0].Instances[0].$2" --output text; }
my_ip()       { curl -s https://checkip.amazonaws.com | tr -d '[:space:]'; }
ssh_cmd()     { ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 "ec2-user@$1" "${@:2}"; }

need_instance() {
  IID=$(instance_id)
  [[ "$IID" == "None" || -z "$IID" ]] && die "No Kafka instance found. Run: ./kafka-ec2.sh up"
}

cmd_up() {
  mkdir -p "$WORK_DIR"
  local vpc sg subnet ami
  vpc=$(vpc_id)
  [[ "$vpc" == "None" ]] && die "No default VPC"

  if [[ -z "$TASK_SG" ]]; then
    TASK_SG=$(aws_ ecs describe-services --cluster "$CLUSTER_NAME" --services "$APP_SERVICE" \
      --query "services[0].networkConfiguration.awsvpcConfiguration.securityGroups[0]" --output text 2>/dev/null || echo None)
  fi
  [[ "$TASK_SG" == "None" || -z "$TASK_SG" ]] && die "Could not find the task SG of '$APP_SERVICE' in cluster '$CLUSTER_NAME'. Check CLUSTER_NAME, or export TASK_SG=sg-..."
  log "App task SG: $TASK_SG"

  sg=$(kafka_sg)
  if [[ "$sg" == "None" ]]; then
    sg=$(aws_ ec2 create-security-group --group-name kafka-sg --description "Kafka broker" \
          --vpc-id "$vpc" --query GroupId --output text)
  fi
  add_ingress "$sg" 9092 "$TASK_SG"
  add_ingress "$sg" 22 "$(my_ip)/32"
  log "Kafka SG: $sg"

  if ! aws_ ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
    aws_ ec2 create-key-pair --key-name "$KEY_NAME" --query KeyMaterial --output text > "$KEY_FILE"
    chmod 400 "$KEY_FILE" 2>/dev/null || true
    log "Created key pair; private key saved to $KEY_FILE (do NOT commit it)"
  elif [[ ! -f "$KEY_FILE" ]]; then
    log "WARNING: key pair '$KEY_NAME' exists in AWS but $KEY_FILE is missing; SSH will not work (EC2 Instance Connect still does)."
  fi

  IID=$(instance_id)
  if [[ "$IID" == "None" || -z "$IID" ]]; then
    cat > "$WORK_DIR/userdata.sh" <<'EOF'
#!/bin/bash
dnf install -y docker
systemctl enable --now docker
TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')
PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/local-ipv4)
docker run -d --name kafka --restart unless-stopped -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$PRIVATE_IP:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  apache/kafka:3.7.0
EOF
    ami=$(aws_ ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
            --query Parameter.Value --output text)
    subnet=$(aws_ ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" "Name=default-for-az,Values=true" \
            --query "Subnets[0].SubnetId" --output text)
    IID=$(aws_ ec2 run-instances --image-id "$ami" --instance-type "$INSTANCE_TYPE" \
          --key-name "$KEY_NAME" --subnet-id "$subnet" --security-group-ids "$sg" \
          --associate-public-ip-address --user-data "file://$WORK_DIR/userdata.sh" \
          --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$KAFKA_NAME}]" \
          --query "Instances[0].InstanceId" --output text)
    log "Launched $IID"
  else
    log "Instance already exists: $IID"
  fi

  aws_ ec2 wait instance-running --instance-ids "$IID"
  local pip
  pip=$(inst_field "$IID" PrivateIpAddress)
  cat <<EOF

============================================================
 Kafka instance : $IID
 Private IP     : $pip
 Public IP      : $(inst_field "$IID" PublicIpAddress)
 Kafka SG       : $sg
 Wait ~2 minutes for Docker + the broker to start, then: ./kafka-ec2.sh status

 Use these when deploying the app:
   export EVENT_PUBLISHER=kafka
   export KAFKA_BOOTSTRAP="$pip:9092"
============================================================
EOF
}

cmd_status() {
  need_instance
  local state pub
  state=$(inst_field "$IID" State.Name)
  pub=$(inst_field "$IID" PublicIpAddress)
  echo "Instance: $IID  state=$state  private=$(inst_field "$IID" PrivateIpAddress)  public=$pub"
  if [[ "$state" == "running" && -f "$KEY_FILE" ]]; then
    ssh_cmd "$pub" "sudo docker ps --format '{{.Names}} {{.Status}}'; sudo docker logs kafka --tail 3" \
      || echo "(SSH not ready yet, or your IP changed: ./kafka-ec2.sh allow-my-ip)"
  fi
}

cmd_allow()       { [[ -z "${1:-}" ]] && die "Usage: allow SG_ID"; add_ingress "$(kafka_sg)" 9092 "$1"; log "Allowed $1 -> Kafka:9092"; }
cmd_allow_my_ip() { add_ingress "$(kafka_sg)" 22 "$(my_ip)/32"; log "SSH open to $(my_ip)"; }

cmd_topics() {
  need_instance
  ssh_cmd "$(inst_field "$IID" PublicIpAddress)" \
    "sudo docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
}

cmd_consume() {
  [[ -z "${1:-}" ]] && die "Usage: consume TOPIC"
  need_instance
  ssh_cmd "$(inst_field "$IID" PublicIpAddress)" \
    "sudo docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic $1 --from-beginning --timeout-ms 20000"
}

cmd_ssh()   { need_instance; ssh_cmd "$(inst_field "$IID" PublicIpAddress)"; }
cmd_stop()  { need_instance; aws_ ec2 stop-instances  --instance-ids "$IID" >/dev/null; log "Stopping $IID"; }
cmd_start() { need_instance; aws_ ec2 start-instances --instance-ids "$IID" >/dev/null
              aws_ ec2 wait instance-running --instance-ids "$IID"
              log "Started. Private IP unchanged: $(inst_field "$IID" PrivateIpAddress). Public IP changed: $(inst_field "$IID" PublicIpAddress)"; }

cmd_destroy() {
  read -r -p "Terminate Kafka instance, delete SG and key pair? Topic data will be lost. (yes/no) " ans
  [[ "$ans" == "yes" ]] || die "Aborted"
  IID=$(instance_id)
  if [[ "$IID" != "None" && -n "$IID" ]]; then
    aws_ ec2 terminate-instances --instance-ids "$IID" >/dev/null
    aws_ ec2 wait instance-terminated --instance-ids "$IID"
  fi
  local sg; sg=$(kafka_sg)
  [[ "$sg" != "None" ]] && aws_ ec2 delete-security-group --group-id "$sg"
  aws_ ec2 delete-key-pair --key-name "$KEY_NAME" >/dev/null 2>&1 || true
  rm -f "$KEY_FILE"
  log "Destroyed. Redeploy the app with EVENT_PUBLISHER=logging so it does not point at a dead broker."
}

case "${1:-}" in
  up)          cmd_up ;;
  status)      cmd_status ;;
  allow)       cmd_allow "${2:-}" ;;
  allow-my-ip) cmd_allow_my_ip ;;
  topics)      cmd_topics ;;
  consume)     cmd_consume "${2:-}" ;;
  ssh)         cmd_ssh ;;
  stop)        cmd_stop ;;
  start)       cmd_start ;;
  destroy)     cmd_destroy ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac