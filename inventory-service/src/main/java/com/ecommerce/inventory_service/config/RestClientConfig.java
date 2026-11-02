package com.ecommerce.inventory_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * >>> INTER-SERVICE CALL (Inventory -> Product): HTTP client configuration.
 * Timeouts are mandatory: without them a slow product-service would hold our threads forever.
 * In AWS the base URL comes from env var CLIENTS_PRODUCT_SERVICE_BASE_URL (product ALB DNS + /api).
 */
@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(
            @Value("${clients.product-service.base-url:http://localhost:8082/api}") String baseUrl,
            @Value("${clients.product-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${clients.product-service.read-timeout-ms:3000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}