# inventory-service

Stock levels and order stock **reservations** (reserve, confirm, release, expire) with an audit ledger.

| Item | Value |
|---|---|
| Stack | Spring Boot **3.3.4**, JDK **21**, Spring Data JPA, PostgreSQL, Flyway, Resilience4j 2.2.0, springdoc 2.6.0, optional Kafka |
| Port / context path | `8083` / `/api` |
| Database | `inventory_database` (PostgreSQL), tables by Flyway: `inventory_items`, `stock_reservations`, `stock_movements` |
| Health | `GET /api/actuator/health` |
| API docs | `/api/swagger-ui.html` |
| Auth (Phase 1) | header `X-Internal-Api-Key`, enforced when `security.internal-api-key` is set (always on AWS, blank locally) |
| AWS | ECS Fargate behind `inventory-service-alb`, logs in `/ecs/inventory-service` |

## API

| Method | Path | Purpose | Success |
|---|---|---|---|
| POST | `/api/inventory/items` | create stock `{productId, quantity, reorderLevel}` | 201 |
| GET | `/api/inventory/items?page=0&size=20` | list stock | 200 |
| GET | `/api/inventory/items/low-stock` | items at/below reorder level | 200 |
| GET | `/api/inventory/items/{productId}` | stock of one product | 200 |
| GET | `/api/inventory/items/{productId}/availability?quantity=N` | can N units be served? | 200 |
| PUT | `/api/inventory/items/{productId}/stock` | adjust stock `{delta, reason}` | 200 |
| GET | `/api/inventory/items/{productId}/movements` | audit ledger | 200 |
| POST | `/api/inventory/reservations` | reserve all items of an order (idempotent per `orderId`) | 201 |
| POST | `/api/inventory/reservations/{orderId}/confirm` | commit the reservation | 200 |
| POST | `/api/inventory/reservations/{orderId}/release` | give the stock back | 200 |
| GET | `/api/inventory/reservations/{orderId}` | reservation state | 200 |

Payloads:
```json
POST /api/inventory/items          { "productId": 1, "quantity": 100, "reorderLevel": 10 }
PUT  /api/inventory/items/1/stock  { "delta": 50, "reason": "Goods received from supplier" }
POST /api/inventory/reservations   { "orderId": "ORD-1001", "items": [ { "productId": 1, "quantity": 2 } ] }
```
Error codes: 400 validation, 404 unknown item/order/route, 409 duplicate item / insufficient stock / invalid state change,
401/403 missing or wrong API key, 503 downstream (product-service) unavailable.

A ready-made **Postman collection** is in [`postman/inventory-service.postman_collection.json`](postman/inventory-service.postman_collection.json)
(import it, set `baseUrl`; on AWS also set `apiKey` and tick the `X-Internal-Api-Key` header in each request).

## Run locally

1. JDK 21 and a local PostgreSQL. Create the empty database `inventory_database` (pgAdmin: right-click Databases > Create).
2. Tell the app your Postgres login. Either edit `src/main/resources/application-local.properties`, or set environment variables
   (defaults are `postgres` / `postgres`):
   ```
   LOCAL_DB_USER=YOUR_USER   LOCAL_DB_PASSWORD=YOUR_PASSWORD   LOCAL_DB_URL=jdbc:postgresql://localhost:5432/inventory_database
   ```
3. Start: `./mvnw spring-boot:run` (Git Bash) / `mvnw.cmd spring-boot:run` (cmd) or run `InventoryServiceApplication` in IntelliJ.
   Events default to `logging`, so no Kafka is needed.
4. `http://localhost:8083/api/actuator/health` returns `UP`.

```bash
./mvnw clean package -DskipTests     # build
./mvnw test                          # 84 tests; repository + integration tests need Docker (Testcontainers)
```
Docker-free subset: `./mvnw test -Dtest='*ControllerTest,*ServiceImplTest,InternalApiKeyFilterTest,ProductClientTest'`.

The Maven wrapper files (`.mvn/`, `mvnw`, `mvnw.cmd`) must be committed together with the project.
This project's wrapper has no jar, so always run `./mvnw` / `mvnw.cmd`, never `java -cp .mvn/wrapper/maven-wrapper.jar ...`.

## Deploy to AWS

From the **repo root** (see [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)):
```bash
export AWS_PROFILE=ecommerce-dev AWS_REGION=us-east-1
export CLUSTER_NAME=ecommerce-inventory-service-cluster ALLOW_NEW_CLUSTER=1
export RDS_ENDPOINT=YOUR-RDS-HOST DB_USERNAME=YOUR_RDS_MASTER_USER DB_PASSWORD=YOUR_RDS_MASTER_PASSWORD
export CREATE_DB=1
bash deploy-inventory-service.sh
```
It generates the API key (`.deploy-inventory-service/internal-api-key.txt`), discovers `product-service-alb`, builds and pushes the image
and prints the base URL. Test it:
```bash
ALB=http://INVENTORY-ALB-DNS/api
KEY=$(tr -d '\r\n ' < .deploy-inventory-service/internal-api-key.txt)
curl -i $ALB/actuator/health
curl -i -X POST $ALB/inventory/items -H "Content-Type: application/json" -H "X-Internal-Api-Key: $KEY" \
  -d '{"productId":1,"quantity":100,"reorderLevel":10}'
curl -i -X POST $ALB/inventory/reservations -H "Content-Type: application/json" -H "X-Internal-Api-Key: $KEY" \
  -d '{"orderId":"ORD-1001","items":[{"productId":1,"quantity":2}]}'
```

### Environment variables on AWS
`SPRING_PROFILES_ACTIVE`, `SPRING_DATASOURCE_URL|USERNAME|PASSWORD`, `JWT_SECRET`, `APP_EVENTS_PUBLISHER`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
`SECURITY_INTERNAL_API_KEY`, `CLIENTS_PRODUCT_SERVICE_ENABLED`, `CLIENTS_PRODUCT_SERVICE_BASE_URL` (set for you by the scripts).

## Inter-service calls

| Direction | What | Default |
|---|---|---|
| inventory -> product-service | `GET {PRODUCT_BASE_URL}/products/{id}` before creating stock (2s connect / 3s read timeout, circuit breaker) | **off** (`clients.product-service.enabled=false`) |
| order-service -> inventory | reserve / confirm / release (the Saga participant side) | order-service not built yet |

Turn on the product check only after verifying the endpoint: `curl http://PRODUCT_ALB/api/products/1` must give 2xx (exists) / 404 (missing).
Then `export ENABLE_PRODUCT_VALIDATION=1` and redeploy. Details: [docs/INVENTORY-SERVICE-DEPLOYMENT-GUIDE.md](../docs/INVENTORY-SERVICE-DEPLOYMENT-GUIDE.md).

## Design (why it is built this way)

Atomic conditional `UPDATE`s (no overselling under concurrency), idempotent reservations keyed by `orderId`, compare-and-set state
transitions, deterministic lock ordering (no deadlocks), reservation TTL (15 min) with an expiry job, audit ledger, DB `CHECK`
constraints, after-commit event publishing, circuit breaker on the Product call. Why PostgreSQL rather than DynamoDB, and the
patterns used: [docs/INVENTORY-DESIGN-NOTES.md](../docs/INVENTORY-DESIGN-NOTES.md).

## Lessons learned

- Local `password authentication failed for user "admin"`: the defaults were guesses; use your own login (env vars above).
- Scripts must be in the **repo root**; inside the service folder the path became `inventory-service/inventory-service`.
- Docker Desktop cannot read Git Bash paths, so the build runs `cd inventory-service && docker build .`.
- A hidden `\r` in a generated secret made the task definition invalid JSON (`Invalid JSON received`); the script now strips it.
- 502 for the first 1-3 minutes after deploy is normal (JVM + Flyway start).

Full list: [docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md).
