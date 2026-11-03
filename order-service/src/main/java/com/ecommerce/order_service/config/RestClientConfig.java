package com.ecommerce.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * >>> INTER-SERVICE CALLS (Order -> Inventory, Order -> Product): HTTP client configuration.
 * Timeouts are mandatory: without them a slow downstream service would hold our threads forever.
 * In AWS the base URLs come from env vars CLIENTS_INVENTORY_SERVICE_BASE_URL / CLIENTS_PRODUCT_SERVICE_BASE_URL
 * (ALB DNS + /api) and the key from CLIENTS_INVENTORY_SERVICE_API_KEY; deploy-order-service.sh sets all of them.
 */
@Configuration
public class RestClientConfig {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(
            @Value("${clients.inventory-service.base-url:http://localhost:8083/api}") String baseUrl,
            @Value("${clients.inventory-service.api-key:}") String apiKey,
            @Value("${clients.inventory-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${clients.inventory-service.read-timeout-ms:5000}") int readTimeoutMs) {

        RestClient.Builder builder = baseBuilder(baseUrl, connectTimeoutMs, readTimeoutMs);
        if (apiKey != null && !apiKey.isBlank()) {
            // sent on every call; inventory-service rejects requests without it (HTTP 401) when it has a key configured
            builder.defaultHeader(API_KEY_HEADER, apiKey);
        }
        return builder.build();
    }

    @Bean("productRestClient")
    public RestClient productRestClient(
            @Value("${clients.product-service.base-url:http://localhost:8082/api}") String baseUrl,
            @Value("${clients.product-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${clients.product-service.read-timeout-ms:3000}") int readTimeoutMs) {
        return baseBuilder(baseUrl, connectTimeoutMs, readTimeoutMs).build();
    }

    private RestClient.Builder baseBuilder(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }
}
