# Stage 1: build (JDK 21 must match pom.xml java.version)
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/order-service-*.jar app.jar
EXPOSE 8084
# JVM start is slow on small Fargate sizes, so give it a long start period.
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8084/api/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
