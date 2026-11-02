package com.ecommerce.inventory_service.client;

import com.ecommerce.inventory_service.exception.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Inventory -> Product inter-service call, tested against a mock HTTP server (no real network).
 * The circuit breaker is an AOP concern and is exercised at runtime, not here.
 */
class ProductClientTest {

    private MockRestServiceServer server;

    private ProductClient client(boolean enabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product.test/api");
        server = MockRestServiceServer.bindTo(builder).build();
        return new ProductClient(builder.build(), enabled, "/products/{id}");
    }

    @Test
    void productExists_returnsTrueWhenProductServiceAnswers2xx() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":7}", org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.productExists(7L)).isTrue();
        server.verify();
    }

    @Test
    void productExists_returnsFalseWhenProductServiceAnswers404() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/8"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.productExists(8L)).isFalse();
        server.verify();
    }

    @Test
    void productExists_throwsExternalServiceExceptionOnServerError() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/9")).andRespond(withServerError());

        assertThatThrownBy(() -> client.productExists(9L))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 500");
        server.verify();
    }

    @Test
    void productExists_throwsExternalServiceExceptionWhenAccessIsForbidden() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/10")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.productExists(10L))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void productExists_assumesTrueAndMakesNoCallWhenValidationIsDisabled() {
        ProductClient client = client(false);

        assertThat(client.productExists(11L)).isTrue();
        server.verify();   // no expectations were set, so any HTTP call would have failed
    }
}