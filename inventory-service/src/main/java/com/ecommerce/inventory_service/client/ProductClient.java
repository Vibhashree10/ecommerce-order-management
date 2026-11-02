package com.ecommerce.inventory_service.client;

import com.ecommerce.inventory_service.exception.ExternalServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * >>> INTER-SERVICE CALL (Inventory -> Product).
 * Adapter / anti-corruption layer: the rest of the service only knows "does this product exist?",
 * not product-service's URLs, status codes or payloads.
 *
 * Calls GET {base-url}{product-path}  (default /products/{id}).
 *   2xx -> true, 404 -> false, anything else / network failure -> ExternalServiceException (HTTP 503).
 * Guarded by a circuit breaker ("productService"): after repeated failures it stops calling
 * product-service for 30s and fails fast instead of piling up slow requests.
 *
 * The @CircuitBreaker proxy only works when this method is called from ANOTHER bean
 * (InventoryServiceImpl does), never via this.productExists(...).
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

    /** Returns true when validation is disabled, so the service still works without product-service. */
    @CircuitBreaker(name = "productService")
    public boolean productExists(Long productId) {
//        if (!enabled) {
//            log.debug("Product validation disabled; assuming product {} exists", productId);
//            return true;
//        }
        try {
            return restClient.get()
                    .uri(productPath, productId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            return true;
                        }
                        if (status == 404) {
                            return false;
                        }
                        throw new ExternalServiceException("product-service returned HTTP " + status
                                + " for product " + productId);
                    });
        } catch (RestClientException e) {
            throw new ExternalServiceException("product-service unreachable: " + e.getMessage(), e);
        }
    }
}