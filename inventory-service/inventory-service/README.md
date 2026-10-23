# inventory-service

Stock levels and order stock reservations (Spring Boot 3.3.4, JDK 21, PostgreSQL, Flyway, optional Kafka).

| Item | Value |
|---|---|
| Port / context path | `8083` / `/api` |
| Database | `inventory_database` (PostgreSQL, schema managed by Flyway) |
| Health | `GET /api/actuator/health` |
| Docs | `/api/swagger-ui.html` |
| Auth (Phase 1) | header `X-Internal-Api-Key` (enforced when `security.internal-api-key` is set) |

## API
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/inventory/items` | create stock for a product `{productId, quantity, reorderLevel}` |
| GET | `/api/inventory/items?page=&size=` | list stock |
| GET | `/api/inventory/items/low-stock` | items at/below reorder level |
| GET | `/api/inventory/items/{productId}` | stock of one product |
| GET | `/api/inventory/items/{productId}/availability?quantity=N` | can N units be served? |
| PUT | `/api/inventory/items/{productId}/stock` | adjust stock `{delta, reason}` |
| GET | `/api/inventory/items/{productId}/movements` | audit ledger |
| POST | `/api/inventory/reservations` | reserve all items of an order `{orderId, items[]}` (idempotent) |
| POST | `/api/inventory/reservations/{orderId}/confirm` | commit the reservation |
| POST | `/api/inventory/reservations/{orderId}/release` | give the stock back |
| GET | `/api/inventory/reservations/{orderId}` | reservation state |

## Run locally
```
docker exec -it ecommerce-postgres psql -U admin -d postgres -c "CREATE DATABASE inventory_database;"
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

## Environment variables on AWS
`SPRING_PROFILES_ACTIVE`, `SPRING_DATASOURCE_URL|USERNAME|PASSWORD`, `APP_EVENTS_PUBLISHER`,
`SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SECURITY_INTERNAL_API_KEY`, `CLIENTS_PRODUCT_SERVICE_ENABLED`,
`CLIENTS_PRODUCT_SERVICE_BASE_URL`. Full walkthrough: `INVENTORY-SERVICE-DEPLOYMENT-GUIDE.md`.

## Not in this folder (copy from auth-service)
`.mvn/` folder, `mvnw`, `mvnw.cmd` (they contain a binary jar).
