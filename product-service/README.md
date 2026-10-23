# Product Service

A microservice for managing products and categories in an e-commerce system. Built with Spring Boot 3.2.0, MySQL, and Kafka.

## Overview

The Product Service is responsible for:
- Product management (CRUD operations)
- Category management
- Product inventory tracking
- Stock management
- Product search and filtering

## Features

- ✅ RESTful API endpoints for product and category management
- ✅ JWT-based security configuration
- ✅ Database migrations with Flyway
- ✅ Kafka integration for async messaging
- ✅ Comprehensive error handling
- ✅ JPA/Hibernate ORM
- ✅ H2 database for local development
- ✅ MySQL for production
- ✅ Full test coverage (unit, integration, and repository tests)
- ✅ Docker support with multi-stage builds
- ✅ CI/CD pipeline with GitHub Actions
- ✅ AWS ECS Task Definition
- ✅ Spring Cloud Eureka integration
- ✅ Actuator health checks

## Technology Stack

- **Framework**: Spring Boot 3.2.0
- **Language**: Java 17
- **Build Tool**: Maven
- **Database**: MySQL 8.0 / H2 (dev)
- **ORM**: Hibernate/JPA
- **Migration**: Flyway
- **Messaging**: Apache Kafka
- **Security**: Spring Security
- **Testing**: JUnit 5, Mockito, Testcontainers
- **Containerization**: Docker

## Project Structure

```
product-service/
├── .github/workflows/           # CI/CD pipeline
├── .infra/                      # Infrastructure files (AWS ECS)
├── src/
│   ├── main/
│   │   ├── java/com/ecommerce/product/
│   │   │   ├── config/          # Configuration classes
│   │   │   ├── controller/      # REST controllers
│   │   │   ├── dto/             # Data transfer objects
│   │   │   ├── entity/          # JPA entities
│   │   │   ├── exception/       # Exception handling
│   │   │   ├── repository/      # JPA repositories
│   │   │   └── service/         # Business logic
│   │   └── resources/           # Configuration files and migrations
│   └── test/                    # Test classes
├── Dockerfile                   # Multi-stage Docker build
└── pom.xml                      # Maven dependencies
```

## API Endpoints

### Products

- `GET /api/v1/products` - Get all products with pagination
- `GET /api/v1/products/{id}` - Get product by ID
- `GET /api/v1/products/sku/{sku}` - Get product by SKU
- `POST /api/v1/products` - Create new product
- `PUT /api/v1/products/{id}` - Update product
- `DELETE /api/v1/products/{id}` - Delete product
- `GET /api/v1/products/search?name={name}` - Search products by name
- `GET /api/v1/products/category/{categoryId}` - Get products by category
- `GET /api/v1/products/low-stock?threshold={n}` - Get low stock products
- `PATCH /api/v1/products/{id}/activate` - Activate product
- `PATCH /api/v1/products/{id}/deactivate` - Deactivate product
- `PATCH /api/v1/products/{id}/stock?quantity={n}` - Update stock quantity

### Categories

- `GET /api/v1/categories` - Get all categories with pagination
- `GET /api/v1/categories/{id}` - Get category by ID
- `POST /api/v1/categories` - Create new category
- `PUT /api/v1/categories/{id}` - Update category
- `DELETE /api/v1/categories/{id}` - Delete category
- `GET /api/v1/categories/search?name={name}` - Search categories by name
- `GET /api/v1/categories/active/all` - Get all active categories
- `PATCH /api/v1/categories/{id}/activate` - Activate category
- `PATCH /api/v1/categories/{id}/deactivate` - Deactivate category
- `GET /api/v1/categories/count/active` - Get count of active categories

## Environment Configuration

### Local Development
```bash
# application-local.properties
# Uses H2 in-memory database
spring.profiles.active=local
```

### Development
```bash
# application-dev.properties
# Connects to MySQL dev database
spring.profiles.active=dev
```

### Production
```bash
# application-prod.properties
# Production-grade configuration
spring.profiles.active=prod
```

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.8+
- Docker (for containerization)
- MySQL 8.0 (for production)

### Local Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd product-service
   ```

2. **Build the application**
   ```bash
   mvn clean install
   ```

3. **Run the application**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
   ```

4. **Access the application**
   - API: http://localhost:8081
   - H2 Console: http://localhost:8081/h2-console

### Docker Setup

1. **Build Docker image**
   ```bash
   docker build -t product-service:latest .
   ```

2. **Run Docker container**
   ```bash
   docker run -p 8081:8081 \
     -e SPRING_PROFILES_ACTIVE=dev \
     -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/product_service_db \
     -e SPRING_DATASOURCE_USERNAME=root \
     -e SPRING_DATASOURCE_PASSWORD=password \
     product-service:latest
   ```

## Running Tests

```bash
# Run all tests
mvn clean test

# Run only unit tests
mvn test -Dgroups="unit"

# Run only integration tests
mvn test -Dgroups="integration"

# Generate test report
mvn test jacoco:report
```

## Building for Production

```bash
# Build JAR with optimizations
mvn clean package -DskipTests -Pprod

# Build Docker image for production
docker build -t product-service:${VERSION} .

# Push to registry
docker push your-registry/product-service:${VERSION}
```

## Database Migrations

Flyway automatically handles database migrations on startup. Migration scripts are located in:
```
src/main/resources/db/migration/
```

### Create New Migration
1. Create a new SQL file: `V{version}__description.sql`
2. Place in `src/main/resources/db/migration/`
3. Restart the application

## Kafka Integration

The service publishes the following Kafka events:
- `product-created` - When a new product is created
- `product-deleted` - When a product is deleted
- `category-created` - When a new category is created
- `category-deleted` - When a category is deleted

Configure Kafka:
```properties
spring.kafka.bootstrap-servers=localhost:9092
```

## Health Checks

The service exposes the following health endpoints:
- `/actuator/health` - Overall health status
- `/actuator/health/liveness` - Liveness probe
- `/actuator/health/readiness` - Readiness probe
- `/actuator/metrics` - Application metrics

## Contributing

1. Create a feature branch: `git checkout -b feature/my-feature`
2. Make your changes and write tests
3. Push to the branch: `git push origin feature/my-feature`
4. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions, please create an issue in the repository.
