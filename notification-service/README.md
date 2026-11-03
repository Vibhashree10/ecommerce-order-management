# notification-service

Consumes order events (Kafka topics `order-created`, `order-confirmed`, `order-cancelled`, `order-rejected`) and records/sends notifications on the LOG channel and, optionally, e-mail.

* Port 8085, context path `/api`, database `notification_database` (Flyway `V1`)
* Spring Boot 3.3.4, JDK 21
* Kafka consumer is **off by default** (`NOTIFICATIONS_KAFKA_ENABLED=true` to enable); events can also be posted to `POST /api/notifications/events`
* API protected by `X-Internal-Api-Key` (`SECURITY_INTERNAL_API_KEY`); `/api/actuator/health` is public

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/notifications?orderNumber&customerId&status&page&size` | list, newest first |
| GET | `/api/notifications/{id}` | one notification |
| POST | `/api/notifications/{id}/retry` | re-send a FAILED notification (409 otherwise) |
| POST | `/api/notifications/events` | handle an order event (same path as Kafka) |

Run locally: set `LOCAL_DB_URL`, `LOCAL_DB_USER`, `LOCAL_DB_PASSWORD`, then `mvnw.cmd spring-boot:run`.
Deploy: see `NOTIFICATION-SERVICE-DEPLOYMENT-GUIDE.md`. Design: `NOTIFICATION-DESIGN-NOTES.md`.
