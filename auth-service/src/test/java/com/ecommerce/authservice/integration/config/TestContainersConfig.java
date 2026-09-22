package com.ecommerce.authservice.integration.config;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration
public class TestContainersConfig {

    @Bean
    public PostgreSQLContainer postgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")) // Removed <?> here
                .withDatabaseName("auth_db_test")
                .withUsername("test_user")
                .withPassword("test_password")
                .withReuse(true);
        container.start();
        return container;
    }
}
