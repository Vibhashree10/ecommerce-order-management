package com.ecommerce.order_service.client;

import com.ecommerce.order_service.exception.ExternalServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * >>> INTER-SERVICE CALL (Order -> Product). Adapter / anti-corruption layer: looks up the catalog data
 * (sku, name, price) that is snapshotted onto the order line.
 *   2xx -> product, 404 -> empty, anything else / network failure -> ExternalServiceException (HTTP 503).
 * Disabled by default (clients.product-service.enabled=false); then the caller must send unitPrice.
 * Guarded by circuit breaker "productService" (works only when called from another bean, never via this.find(...)).
 */
@Slf4j
@Component
public class ProductClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String productPath;

    public ProductClient(@Qualifier("productRestClient") RestClient productRestClient,
                         @Value("${clients.product-service.enabled:false}") boolean enabled,
                         @Value("${clients.product-service.product-path:/products/{id}}") String productPath) {
        this.restClient = productRestClient;
        this.enabled = enabled;
        this.productPath = productPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @CircuitBreaker(name = "productService")
    public Optional<ProductInfo> find(Long productId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return restClient.get()
                    .uri(productPath, productId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            ProductInfo info = response.bodyTo(ProductInfo.class);
                            if (info == null) {
                                throw new ExternalServiceException("product-service returned an empty body for product " + productId);
                            }
                            return Optional.of(info);
                        }
                        if (status == 404) {
                            return Optional.<ProductInfo>empty();
                        }
                        throw new ExternalServiceException("product-service returned HTTP " + status + " for product " + productId);
                    });
        } catch (RestClientException e) {
            throw new ExternalServiceException("product-service unreachable: " + e.getMessage(), e);
        }
    }
}
