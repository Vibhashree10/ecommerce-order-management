package com.ecommerce.order_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The parts of product-service's GET /products/{id} response that order-service needs.
 * ASSUMPTION to verify against your product-service: fields id, sku, name, price and (optionally) active.
 * Unknown fields are ignored; a missing "active" counts as active.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductInfo(Long id, String sku, String name, BigDecimal price, Boolean active) {

    public boolean isActive() {
        return active == null || active;
    }
}
