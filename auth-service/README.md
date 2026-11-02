# auth-service

User registration, login and JWT issuing for the e-commerce platform.

| Item | Value |
|---|---|
| Stack | Spring Boot **3.3.4**, JDK **21**, Spring Security, JWT (jjwt 0.12.5), PostgreSQL, Flyway, springdoc 2.6.0 |
| Port / context path | `8081` / `/api` (every URL, including health, starts with `/api`) |
| Database | `auth_database` (PostgreSQL). Tables are created by Flyway on startup (`users`, `refresh_tokens`, ...) |
| Health | `GET /api/actuator/health` |
| API docs | `/api/swagger-ui.html` |
| AWS | ECS Fargate behind `auth-service-alb`, logs in CloudWatch `/ecs/auth-service` |

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register` | create a user |
| POST | `/api/auth/login` | log in, returns the JWT |

The service also exposes user endpoints (see `UserController`); open **Swagger UI** for the complete, current list with schemas.

Sample payloads:

```json
POST /api/auth/register
{ "email": "jane@test.com", "password": "Secure@123", "firstName": "Jane", "lastName": "Doe" }
```
```json
POST /api/auth/login
{ "email": "jane@test.com", "password": "Secure@123" }
```

```bash
BASE=http://localhost:8081/api            # or http://AUTH-ALB-DNS/api on AWS
curl -i -X POST $BASE/auth/register -H "Content-Type: application/json" \
  -d '{"email":"jane@test.com","password":"Secure@123","firstName":"Jane","lastName":"Doe"}'
curl -i -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"email":"jane@test.com","password":"Secure@123"}'
```

## Run locally

1. Install **JDK 21** and PostgreSQL (pgAdmin is fine; Docker is optional). Check `java -version` says 21.
2. Create the empty database once (pgAdmin: right-click Databases > Create > `auth_database`, or `CREATE DATABASE auth_database;`).
   Spring and Flyway do **not** create the database, only the tables inside it.
3. Put your local Postgres login in `src/main/resources/application-local.properties`
   (`spring.datasource.url=jdbc:postgresql://localhost:5432/auth_database`, username, password).
4. Run:
   ```bash
   ./mvnw spring-boot:run            # Git Bash   |   mvnw.cmd spring-boot:run   (cmd)   |   .\mvnw.cmd spring-boot:run (PowerShell)
   ```
   `application.properties` selects the `local` profile. **Only** `application.properties` may contain `spring.profiles.active`.
5. Check `http://localhost:8081/api/actuator/health` returns `{"status":"UP"}`.

Build and test:
```bash
./mvnw clean package -DskipTests      # build the jar
./mvnw test                           # unit/controller tests; repository/integration tests need Docker (Testcontainers)
```

## Configuration

| Profile file | Used when |
|---|---|
| `application.properties` | always; sets port, context path, `spring.profiles.active=local` |
| `application-local.properties` | your laptop (local Postgres) |
| `application-dev.properties` | AWS (`SPRING_PROFILES_ACTIVE=dev`), reads `${SPRING_DATASOURCE_URL}`, `${SPRING_DATASOURCE_USERNAME}`, `${SPRING_DATASOURCE_PASSWORD}`, `${JWT_SECRET}` |

## Deploy to AWS

From the **repo root** (see [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)):
```bash
export AWS_PROFILE=ecommerce-dev AWS_REGION=us-east-1
export CLUSTER_NAME=YOUR_AUTH_CLUSTER            # for example the existing ecommerce-auth-service-cluster
export RDS_ENDPOINT=YOUR-RDS-HOST DB_USERNAME=YOUR_RDS_MASTER_USER DB_PASSWORD=YOUR_RDS_MASTER_PASSWORD
export CREATE_DB=1                               # first time only
bash deploy-auth-service.sh
```
The script generates a JWT secret once and keeps it in `.deploy-auth-service/jwt-secret.txt`. If auth-service is already
running from a manual deployment, either export your existing `JWT_SECRET` first or accept that existing tokens become invalid
when the new revision starts.

## Lessons learned (all verified the hard way)

- **Boot 4.x silently skipped Flyway** (`relation "users" does not exist`). Stay on **3.3.4**.
- Health returned **403** until `SecurityConfig` allowed it: `.requestMatchers(EndpointRequest.to("health")).permitAll()`.
- With context path `/api` the ALB health check path must be `/api/actuator/health`.
- Docker image must use **JDK 21 in both stages**.
- Flyway scripts must be **PostgreSQL syntax** (not MySQL).
- `spring.profiles.active` inside `application-dev.properties` fails startup with `InvalidConfigDataPropertyException`.
- Calling a non-existent route returns 500 here (generic handler); check you are calling the right service.

Full list with fixes: [docs/TROUBLESHOOTING.md](../docs/TROUBLESHOOTING.md).

## Files NOT to commit
`.deploy-*/` (JWT secret), `target/`, `.idea/`. They are already in the root `.gitignore`.
