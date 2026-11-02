# product-service

Product catalogue: categories and products (CRUD, search, stock counter, activate/deactivate), publishing domain events.

| Item | Value |
|---|---|
| Stack | Spring Boot **3.3.4**, JDK **21**, Spring Data JPA, PostgreSQL, Flyway, springdoc 2.6.0, optional Kafka |
| Port / context path | `8082` / `/api` |
| Database | `product_database` (PostgreSQL), schema by Flyway |
| Health | `GET /api/actuator/health` |
| API docs | `/api/swagger-ui.html` (the complete, current endpoint list) |
| AWS | ECS Fargate behind `product-service-alb`, logs in `/ecs/product-service` |

## What it offers

- **Categories**: create, get by id, list (paged), list active, search by name, update, delete, activate/deactivate, count.
  Example (create takes query parameters): `POST /api/category?name=Electronics&description=Electronic products&imageUrl=https://example.com/e.jpg`
- **Products**: create, get by id, get by SKU, list (paged), search by name, list by category, update, delete, activate/deactivate,
  low-stock report, stock adjustment, count per category.
  Create-product fields: `sku`, `name`, `description`, `price`, `quantity`, `categoryId`, `manufacturer`, `imageUrl`.
- **Events** (through the `EventPublisher` abstraction): `product-created`, `product-deleted`, `category-created`, `category-deleted`.

Exact URLs and request bodies: open **Swagger UI** (`/api/swagger-ui.html`). Inventory-service assumes `GET /api/products/{id}`
returns 2xx for an existing product and 404 for a missing one; verify that before enabling product validation there.

## Run locally

1. JDK 21 and a local PostgreSQL. Create the empty database `product_database` (pgAdmin or `CREATE DATABASE product_database;`).
2. Put your Postgres login in `src/main/resources/application-local.properties`.
3. Keep events off locally: `app.events.publisher=logging` (no Kafka needed).
4. `./mvnw spring-boot:run` (Git Bash) / `mvnw.cmd spring-boot:run` (cmd).
5. `http://localhost:8082/api/actuator/health` must return `UP`.

If the log says `No active profile set` and `Tomcat initialized with port 8080`, your `application.properties` was not loaded
(missing/empty/misnamed): see [TROUBLESHOOTING](../docs/TROUBLESHOOTING.md#3-spring-configuration-and-profiles).

## Events and Kafka

All publishing goes through `EventPublisher` (`event/` package):

| `app.events.publisher` | Behaviour |
|---|---|
| `logging` (default) | writes `EVENT [topic] ...` to the log; never blocks |
| `kafka` | sends to Kafka **after the DB transaction commits**, with `max.block.ms=5000` so an unreachable broker cannot hang a request |

This replaced direct `KafkaTemplate.send()` calls, which hung requests for ~60 s when the broker was down.
Run a broker on EC2 with `bash kafka-ec2.sh up`; see [docs/KAFKA-INTEGRATION-GUIDE.md](../docs/KAFKA-INTEGRATION-GUIDE.md).

## Deploy to AWS

From the **repo root** (see [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)):
```bash
export AWS_PROFILE=ecommerce-dev AWS_REGION=us-east-1
export CLUSTER_NAME=YOUR_PRODUCT_CLUSTER         # exact name from: aws ecs list-clusters
export RDS_ENDPOINT=YOUR-RDS-HOST DB_USERNAME=YOUR_RDS_MASTER_USER DB_PASSWORD=YOUR_RDS_MASTER_PASSWORD
export CREATE_DB=1                               # first time only
bash deploy-product-service.sh
# with Kafka:  export EVENT_PUBLISHER=kafka KAFKA_BOOTSTRAP=PRIVATE_IP:9092
```
product-service reuses auth-service's JWT secret when `.deploy-auth-service/jwt-secret.txt` exists, so tokens stay compatible
if it validates them.

## Lessons learned

- Each service needs its **own database**; RDS does not create it (`CREATE_DB=1`, and `DB_USERNAME` must be the RDS master user).
- Task SG must allow the container port from the ALB SG, and the RDS SG must allow 5432 from the task SG.
- Deploy with a **unique image tag and an explicit task-definition revision** (stale revisions caused "old code still running").
- Unreachable Kafka made REST calls hang; fixed by `EventPublisher` + after-commit publishing + `max.block.ms`.
- Lombok `TypeTag :: UNKNOWN` = wrong JDK (26) on `PATH`; use JDK 21.

Full list: [docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md).
