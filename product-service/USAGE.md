# Product Service - Usage Examples

## Example API Calls

### Authentication Setup
The service uses Spring Security with CORS enabled. For production, configure appropriate authentication.

### 1. Category Management Examples

#### Create a Category
```bash
curl -X POST "http://localhost:8081/api/v1/categories" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Electronics",
    "description": "Electronic products",
    "imageUrl": "https://example.com/electronics.jpg"
  }'
```

#### Get All Categories
```bash
curl -X GET "http://localhost:8081/api/v1/categories?page=0&size=10"
```

#### Get Active Categories
```bash
curl -X GET "http://localhost:8081/api/v1/categories/active/all"
```

#### Search Categories
```bash
curl -X GET "http://localhost:8081/api/v1/categories/search?name=electronics"
```

#### Update a Category
```bash
curl -X PUT "http://localhost:8081/api/v1/categories/1" \
  -d "name=Updated Electronics" \
  -d "description=Updated Description"
```

### 2. Product Management Examples

#### Create a Product
```bash
curl -X POST "http://localhost:8081/api/v1/products" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "PROD-001",
    "name": "Laptop Pro",
    "description": "High-performance laptop",
    "price": 1299.99,
    "quantity": 50,
    "categoryId": 1,
    "manufacturer": "TechCorp",
    "imageUrl": "https://example.com/laptop.jpg"
  }'
```

#### Get All Products
```bash
curl -X GET "http://localhost:8081/api/v1/products?page=0&size=10&sortBy=name&direction=ASC"
```

#### Get Product by ID
```bash
curl -X GET "http://localhost:8081/api/v1/products/1"
```

#### Get Product by SKU
```bash
curl -X GET "http://localhost:8081/api/v1/products/sku/PROD-001"
```

#### Search Products by Name
```bash
curl -X GET "http://localhost:8081/api/v1/products/search?name=laptop&page=0&size=10"
```

#### Get Products by Category
```bash
curl -X GET "http://localhost:8081/api/v1/products/category/1?page=0&size=10"
```

#### Get Low Stock Products
```bash
curl -X GET "http://localhost:8081/api/v1/products/low-stock?threshold=10"
```

#### Update a Product
```bash
curl -X PUT "http://localhost:8081/api/v1/products/1" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Laptop Pro Max",
    "price": 1499.99,
    "quantity": 45
  }'
```

#### Update Product Stock
```bash
curl -X PATCH "http://localhost:8081/api/v1/products/1/stock?quantity=10"
```

#### Deactivate a Product
```bash
curl -X PATCH "http://localhost:8081/api/v1/products/1/deactivate"
```

#### Activate a Product
```bash
curl -X PATCH "http://localhost:8081/api/v1/products/1/activate"
```

#### Delete a Product
```bash
curl -X DELETE "http://localhost:8081/api/v1/products/1"
```

### 3. Health & Monitoring

#### Health Check
```bash
curl -X GET "http://localhost:8081/actuator/health"
```

#### Liveness Probe
```bash
curl -X GET "http://localhost:8081/actuator/health/liveness"
```

#### Readiness Probe
```bash
curl -X GET "http://localhost:8081/actuator/health/readiness"
```

#### Application Metrics
```bash
curl -X GET "http://localhost:8081/actuator/metrics"
```

## Running Different Environments

### Local Development
```bash
# Using Maven
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"

# The H2 console is available at: http://localhost:8081/h2-console
# Username: sa
# Password: (empty)
```

### Development Environment
```bash
# Using Maven with dev profile
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"

# Using Docker Compose
docker-compose up -d

# This starts:
# - MySQL database
# - Apache Kafka with Zookeeper
# - Product Service application
```

### Production Environment
```bash
# Build JAR
mvn clean package -DskipTests -Pprod

# Run with production profile
java -jar target/product-service-1.0.0.jar --spring.profiles.active=prod

# Required environment variables:
# - DB_USERNAME: Database username
# - DB_PASSWORD: Database password
# - KAFKA_BOOTSTRAP_SERVERS: Kafka server addresses
# - EUREKA_SERVER_URL: Eureka server URL
```

## Testing the Application

### Run All Tests
```bash
mvn clean test
```

### Run Specific Test Suite
```bash
# Controller tests only
mvn test -Dtest=ProductControllerTest

# Service tests only
mvn test -Dtest=ProductServiceImplTest

# Integration tests only
mvn test -Dtest=*IntegrationTest
```

### Generate Test Report
```bash
mvn test jacoco:report

# Report location: target/site/jacoco/index.html
```

## Database Migrations

### View Current Schema
The database schema is automatically created on first run via Flyway migrations.

### Add New Migration
1. Create file: `src/main/resources/db/migration/V3__your_migration.sql`
2. Write SQL statements
3. Restart the application (automatic migration on startup)

Example migration file:
```sql
-- V3__add_product_ratings.sql
ALTER TABLE products ADD COLUMN rating DECIMAL(3,2);
ALTER TABLE products ADD COLUMN review_count INT DEFAULT 0;
CREATE INDEX idx_rating ON products(rating);
```

## Kafka Integration

### Monitor Kafka Topics
```bash
# List all topics
docker exec product-service-kafka kafka-topics --list --bootstrap-server localhost:9092

# Consume messages from a topic
docker exec product-service-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic product-created \
  --from-beginning
```

### Topics Published
- `product-created` - Triggered when a product is created
- `product-deleted` - Triggered when a product is deleted
- `category-created` - Triggered when a category is created
- `category-deleted` - Triggered when a category is deleted

## Docker Operations

### Build Docker Image
```bash
docker build -t product-service:latest .
```

### Run Single Container
```bash
docker run -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/product_service_db \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=password \
  product-service:latest
```

### Using Docker Compose (Recommended)
```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f product-service

# Stop all services
docker-compose down

# Clean up volumes (WARNING: Deletes database)
docker-compose down -v
```

## CI/CD Pipeline

The GitHub Actions workflow automatically:
1. Builds the Maven project
2. Runs all tests
3. Generates test reports
4. Builds Docker image
5. Pushes to Docker registry on merge to main/develop

View pipeline status: `.github/workflows/deploy.yml`

## Troubleshooting

### Port Already in Use
```bash
# Kill process on port 8081
lsof -ti:8081 | xargs kill -9

# Or change port in application-local.properties
server.port=8082
```

### Database Connection Issues
```bash
# Check if MySQL is running (Docker Compose)
docker-compose ps

# View MySQL logs
docker-compose logs mysql

# Restart MySQL
docker-compose restart mysql
```

### Kafka Connection Issues
```bash
# Check Kafka status
docker-compose logs kafka

# Restart Kafka
docker-compose restart kafka zookeeper
```

### View Application Logs
```bash
# Docker Compose logs
docker-compose logs -f product-service

# Maven run
mvn spring-boot:run | grep -i error
```

## Performance Optimization

### Database Connection Pooling
Configured in `application-prod.properties`:
- max-pool-size: 10
- min-idle: 5
- connection-timeout: 30000ms

### Hibernate Batch Processing
- batch_size: 30 (configured in production)
- order_inserts: true
- order_updates: true

### Caching (Optional Enhancement)
To add caching, add to pom.xml:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

Then use @Cacheable annotations on service methods.

## Security Considerations

1. **Credentials**: Use environment variables for sensitive data
2. **CORS**: Configure appropriate origins for production
3. **SSL/TLS**: Enable in production (see application-prod.properties)
4. **Authentication**: Implement JWT or OAuth2 for API protection
5. **Rate Limiting**: Consider adding rate limiting middleware

## Next Steps

1. Add API documentation (Swagger/OpenAPI)
2. Implement distributed tracing (Sleuth + Zipkin)
3. Add caching layer (Redis)
4. Implement API versioning
5. Add request/response logging
6. Set up monitoring and alerting
7. Configure load balancing
8. Implement circuit breaker pattern (Hystrix/Resilience4j)
