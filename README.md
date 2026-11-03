# E-Commerce Order Management Platform

Spring Boot **microservices** (JDK 21, Spring Boot 3.3.4, PostgreSQL + Flyway) deployed to **AWS ECS Fargate**
behind Application Load Balancers, with RDS PostgreSQL and CloudWatch Logs. Optional Kafka for domain events.

AWS is used for **deployment** (ECR, ECS Fargate, ALB, RDS, CloudWatch). Phase 2 adds SNS/SQS, Secrets Manager,
API Gateway, Multi-AZ, X-Ray and CI/CD.

## Services

| Service | Port | DB | Status | README |
|---|---|---|---|---|
| [auth-service](auth-service/README.md) | 8081 | `auth_database` | deployed to AWS | registration, login, JWT |
| [product-service](product-service/README.md) | 8082 | `product_database` | deployed to AWS | categories and products, events |
| [inventory-service](inventory-service/README.md) | 8083 | `inventory_database` | deployed to AWS | stock, reservations, ledger |
| [order-service](order-service/README.md) | 8084 | `order_database` | deployed to AWS | orders, Saga against inventory, idempotency, recovery job |
| payment-service | - | - | planned | |
| [notification-service](notification-service/README.md) | 8085 | `notification_database` | ready to deploy | order events (Kafka, optional) to LOG / e-mail notifications, retry |
| [api-gateway](api-gateway/README.md) | 8080 | none | ready to deploy (deploy auth first) | single entry point: JWT check, routing, internal keys |
| [common-lib](common-lib/README.md) | - | - | library, not deployed, not yet used by services | shared event/header/error contracts |

Every service uses context path **`/api`**, so health is `GET /api/actuator/health` and all endpoints start with `/api`.
Each service has its **own database** on a shared PostgreSQL server (database per service).

```
                         +-----------------+
   client / Postman ---> |  ALB (per svc)  | ---> ECS Fargate task (Spring Boot) ---> RDS PostgreSQL (one DB per service)
                         +-----------------+                   |
                                                               +--> CloudWatch Logs  /ecs/SERVICE
   order-service --reserve/confirm/release--> inventory-service --(optional)--> product-service
   order-service --(optional)--> product-service  (price, sku, name snapshot)
   product-service / inventory-service / order-service --events--> Kafka on EC2 (optional, default: logging)
```

## Repository layout

```
.
├── deploy-service.sh               generic AWS deploy engine (ECR + ECS Fargate + ALB + CloudWatch, idempotent)
├── deploy-auth-service.sh          one-command deploy of auth-service
├── deploy-product-service.sh       one-command deploy of product-service
├── deploy-inventory-service.sh     one-command deploy of inventory-service
├── deploy-order-service.sh         one-command deploy of order-service (deploy inventory-service first)
├── deploy-notification-service.sh  one-command deploy of notification-service (LOG channel; Kafka and e-mail optional)
├── deploy-api-gateway.sh           one-command deploy of api-gateway (no database; needs engine v4.3)
├── kafka-ec2.sh                    optional single-node Kafka on EC2
├── deploy.env.example              the variables the scripts read (copy, never commit real values)
├── .gitignore  .gitattributes      secrets/build output ignored; *.sh forced to LF line endings
├── auth-service/                   pom.xml, Dockerfile, mvnw, .mvn/, src/
├── product-service/
├── inventory-service/              + postman/ collection
├── order-service/                  + postman/ collection
├── notification-service/           + postman/ collection
├── api-gateway/                    + postman/ collection
├── common-lib/                     plain jar with shared contracts (see its README)
└── docs/
    ├── DEPLOYMENT.md               how to deploy, redeploy, roll back, options
    ├── TROUBLESHOOTING.md          every problem we hit, with cause and fix
    ├── KAFKA-INTEGRATION-GUIDE.md  optional Kafka on EC2
    ├── INVENTORY-SERVICE-DEPLOYMENT-GUIDE.md
    ├── INVENTORY-DESIGN-NOTES.md   DB choice, inter-service calls, design patterns
    ├── ORDER-SERVICE-DEPLOYMENT-GUIDE.md
    ├── ORDER-DESIGN-NOTES.md       Saga, idempotency, failure modes
    ├── NOTIFICATION-SERVICE-DEPLOYMENT-GUIDE.md / NOTIFICATION-DESIGN-NOTES.md
    └── API-GATEWAY-DEPLOYMENT-GUIDE.md / API-GATEWAY-DESIGN-NOTES.md
```

All scripts sit in the repo root; each service folder holds `pom.xml` and `Dockerfile` directly.

## Quick start (local)

1. Install **JDK 21** (check `java -version`), **Git**, and PostgreSQL with pgAdmin (Docker is optional).
2. Create four empty databases: `auth_database`, `product_database`, `inventory_database`, `order_database`.
3. In each service, put your Postgres login in `src/main/resources/application-local.properties`.
4. Build and run a service (Git Bash; use `mvnw.cmd` in cmd, `.\mvnw.cmd` in PowerShell):
   ```bash
   cd inventory-service
   ./mvnw clean package -DskipTests
   ./mvnw spring-boot:run
   ```
5. `curl http://localhost:8083/api/actuator/health` returns `{"status":"UP"}`. Flyway creates the tables on first start.

The Maven wrapper files (`.mvn/`, `mvnw`, `mvnw.cmd`) are committed. Use them instead of installing Maven.

## Quick start (AWS)

Prerequisites: AWS CLI v2 with a profile, Docker Desktop running, Git Bash, an RDS PostgreSQL instance.
```bash
export AWS_PROFILE=ecommerce-dev AWS_REGION=us-east-1
export CLUSTER_NAME=ecommerce-inventory-service-cluster ALLOW_NEW_CLUSTER=1   # one cluster per service
export RDS_ENDPOINT=YOUR-RDS-HOST DB_USERNAME=YOUR_RDS_MASTER_USER DB_PASSWORD=YOUR_RDS_MASTER_PASSWORD
export CREATE_DB=1
bash deploy-inventory-service.sh        # or deploy-auth-service.sh / deploy-product-service.sh
# order-service needs inventory-service deployed first (it reads inventory's API key and load balancer):
# bash deploy-order-service.sh
# notification-service (optional Kafka/e-mail) and api-gateway (deploy LAST; no database, reads other services' keys):
# bash deploy-notification-service.sh
# bash deploy-api-gateway.sh
```
The script prints the load balancer URL and a health check result. Full guide: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
Problems: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## What the deploy script guarantees

- **Idempotent**: re-running reuses existing AWS resources.
- **No stale code**: unique image tag per run, new task definition revision, service pinned to that exact revision.
- **Fails loudly**: security group rules, database creation, missing cluster all stop the script with an explanation.
- **Safe on Windows**: Git Bash path conversion disabled, CR characters stripped, Docker build via relative context.
- **Refuses to create a cluster** unless `ALLOW_NEW_CLUSTER=1` (a typo cannot create a wrong cluster).
- Generates and re-uses secrets (JWT secret, internal API key) in git-ignored `.deploy-*/` folders.

## Security notes

- Never commit `.deploy-*/`, `deploy.env`, `*.pem`. They are in `.gitignore`.
- Phase 1 keeps DB password, JWT secret and API key as ECS **environment variables**. Move them to Secrets Manager (Phase 2).
- The ALB is HTTP only; add an ACM certificate (HTTPS) before real traffic. RDS is publicly reachable in the dev setup; restrict it
  to the VPC for production.
- inventory-service and order-service are **internal** APIs protected with the `X-Internal-Api-Key` header; order-service calls inventory-service with inventory's key
  (`deploy-order-service.sh` reads it from `.deploy-inventory-service/internal-api-key.txt` and checks it before building).
- Rotate any password that has been pasted into a chat, ticket or terminal history.

## Roadmap

payment-service (new Saga step between reserved and confirmed), adopting `common-lib` through a package repository, per-user authorization in order-service, restricting services to the gateway;
Phase 2: SNS/SQS, Secrets Manager, API Gateway, Multi-AZ, X-Ray, CI/CD (GitHub Actions to ECR/ECS).
