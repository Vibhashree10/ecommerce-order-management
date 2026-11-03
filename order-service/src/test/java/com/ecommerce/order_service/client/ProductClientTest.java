package com.ecommerce.order_service.client;

import com.ecommerce.order_service.exception.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Order -> Product inter-service call against a mock HTTP server. */
class ProductClientTest {

    private MockRestServiceServer server;

    private ProductClient client(boolean enabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product.test/api");
        server = MockRestServiceServer.bindTo(builder).build();
        return new ProductClient(builder.build(), enabled, "/products/{id}");
    }

    @Test
    void find_readsSkuNameAndPriceAndIgnoresUnknownFields() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":7,\"sku\":\"SKU-7\",\"name\":\"Mouse\",\"price\":19.99,\"active\":true,\"category\":{\"id\":1}}",
                        MediaType.APPLICATION_JSON));

        Optional<ProductInfo> product = client.find(7L);

        assertThat(product).isPresent();
        assertThat(product.get().sku()).isEqualTo("SKU-7");
        assertThat(product.get().price()).isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(product.get().isActive()).isTrue();
        server.verify();
    }

    @Test
    void find_missingActiveFieldCountsAsActive() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/7"))
                .andRespond(withSuccess("{\"id\":7,\"price\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.find(7L).orElseThrow().isActive()).isTrue();
    }

    @Test
    void find_404returnsEmpty() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/8")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.find(8L)).isEmpty();
    }

    @Test
    void find_serverErrorThrowsExternalServiceException() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/9")).andRespond(withServerError());

        assertThatThrownBy(() -> client.find(9L))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void find_unauthorizedThrowsExternalServiceExceptionNotNotFound() {
        ProductClient client = client(true);
        server.expect(requestTo("http://product.test/api/products/10")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.find(10L))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void whenDisabled_noCallIsMadeAndResultIsEmpty() {
        ProductClient client = client(false);

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.find(1L)).isEmpty();
        server.verify();
    }
}
