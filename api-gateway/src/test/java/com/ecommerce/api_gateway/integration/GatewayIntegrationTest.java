package com.ecommerce.api_gateway.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.ecommerce.api_gateway.support.TestTokens;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real gateway on a random port, real security chain; the backend services are one MockWebServer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "gateway.routes.order.key=order-secret-key",
        "gateway.routes.product.authenticated=true"})
class GatewayIntegrationTest {

    static MockWebServer backend = new MockWebServer();

    @BeforeAll
    static void start() throws IOException {
        backend.start();
    }

    @AfterAll
    static void stop() throws IOException {
        backend.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String url = "http://localhost:" + backend.getPort();
        r.add("gateway.jwt.secret", () -> TestTokens.SECRET);
        r.add("gateway.routes.auth.url", () -> url);
        r.add("gateway.routes.product.url", () -> url);
        r.add("gateway.routes.order.url", () -> url);
        r.add("gateway.routes.inventory.url", () -> url);   // present but disabled by default
    }

    @Autowired WebTestClient client;

    private RecordedRequest take() throws InterruptedException {
        return backend.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    void health_isPublic() {
        client.get().uri("/api/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void protectedRoute_withoutToken_is401Json() {
        client.get().uri("/api/orders").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void validToken_isProxied_withInternalKeyAndUserHeaders_andSpoofedHeadersRemoved() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));

        client.post().uri("/api/orders")
                .header("Authorization", "Bearer " + TestTokens.rawKeyToken("alice@x.com"))
                .header("X-Internal-Api-Key", "attacker-key")
                .header("X-User-Id", "someone-else")
                .header("Content-Type", "application/json")
                .bodyValue("{\"a\":1}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.ok").isEqualTo(true);

        RecordedRequest seen = take();
        assertThat(seen.getPath()).isEqualTo("/api/orders");
        assertThat(seen.getMethod()).isEqualTo("POST");
        assertThat(seen.getHeaders().values("X-Internal-Api-Key")).containsExactly("order-secret-key");
        assertThat(seen.getHeaders().values("X-User-Id")).containsExactly("alice@x.com");
        assertThat(seen.getHeader("X-Request-Id")).isNotBlank();
        assertThat(seen.getHeader("Authorization")).startsWith("Bearer ");
        assertThat(seen.getBody().readString(StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void base64DecodedKeyToken_isAccepted() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));
        client.get().uri("/api/products?page=0")
                .header("Authorization", "Bearer " + TestTokens.decodedKeyToken("bob@x.com", JWSAlgorithm.HS512, 300))
                .exchange().expectStatus().isOk();
        RecordedRequest seen = take();
        assertThat(seen.getPath()).isEqualTo("/api/products?page=0");
        assertThat(seen.getHeader("X-Internal-Api-Key")).isNull();   // product route has no key configured
    }

    @Test
    void expiredOrForgedToken_is401() {
        client.get().uri("/api/orders").header("Authorization", "Bearer " + TestTokens.decodedKeyToken("a", JWSAlgorithm.HS512, -3600))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/orders").header("Authorization", "Bearer abc.def.ghi")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void login_and_register_arePublic() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(200).setBody("{\"token\":\"t\"}"));
        backend.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
        client.post().uri("/api/auth/login").header("Content-Type", "application/json").bodyValue("{}").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/register").header("Content-Type", "application/json").bodyValue("{}").exchange().expectStatus().isCreated();
        take();
        take();
    }

    @Test
    void otherAuthEndpoints_needAToken() {
        client.get().uri("/api/users/1").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void inventory_isNotExposedByDefault() {
        client.get().uri("/api/inventory/1")
                .header("Authorization", "Bearer " + TestTokens.rawKeyToken("a@x.com"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void backendDown_returns5xx_notAHang() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(503).setBody("{\"message\":\"down\"}"));
        client.get().uri("/api/orders/ORD-1")
                .header("Authorization", "Bearer " + TestTokens.rawKeyToken("a@x.com"))
                .exchange().expectStatus().isEqualTo(503);
        take();
    }
}
