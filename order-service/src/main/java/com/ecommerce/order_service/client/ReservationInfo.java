package com.ecommerce.order_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The parts of inventory-service's reservation response that order-service needs. status: PENDING, CONFIRMED, RELEASED, EXPIRED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationInfo(String orderId, String status, String expiresAt) {

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }
}
