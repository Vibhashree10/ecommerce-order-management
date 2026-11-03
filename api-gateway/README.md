# api-gateway

Spring Cloud Gateway (reactive) in front of the microservices. Port 8080, health `/api/actuator/health`.

* Validates the JWT (shared secret `JWT_SECRET`), forwards `/api/...` unchanged
* Public: `POST /api/auth/register`, `POST /api/auth/login`, health. Everything else needs `Authorization: Bearer ...`
* Adds `X-Internal-Api-Key` (per service), `X-User-Id`, `X-User-Email`, `X-Request-Id`; strips client-supplied versions
* Routes: auth (`/api/auth/**`, `/api/users/**`), product (`/api/products/**`, `/api/category/**`, `/api/categories/**`), order (`/api/orders/**`), notification (off by default), inventory (off by default)

Configuration (env): `JWT_SECRET`, `GATEWAY_ROUTES_{AUTH,PRODUCT,ORDER,NOTIFICATION,INVENTORY}_{URL,KEY,ENABLED,AUTHENTICATED}`, `GATEWAY_CORS_ORIGINS`.
Local run: start the services, export `JWT_SECRET`, `mvnw.cmd spring-boot:run` (profile `local` points to localhost:8081/8082/8084).
Deploy: `API-GATEWAY-DEPLOYMENT-GUIDE.md`. Design: `API-GATEWAY-DESIGN-NOTES.md`.
