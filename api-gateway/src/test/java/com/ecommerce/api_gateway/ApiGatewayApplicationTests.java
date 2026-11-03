package com.ecommerce.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.profiles.active=test", "gateway.jwt.secret=test-secret-value-test-secret-value-test-secret-value-1234"})
class ApiGatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
