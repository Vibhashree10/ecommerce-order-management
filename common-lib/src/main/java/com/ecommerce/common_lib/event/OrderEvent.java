package com.ecommerce.common_lib.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload of the order events (JSON). Same field names as order-service publishes today.
 * Readers ignore unknown fields so the contract can grow. customerEmail is optional (not sent yet).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
        String eventType,
        String orderNumber,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String reason,
        Instant occurredAt,
        String customerEmail) {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";

    public static OrderEvent of(String eventType, String orderNumber, String customerId, String status,
                                BigDecimal totalAmount, String currency, String reason) {
        return new OrderEvent(eventType, orderNumber, customerId, status, totalAmount, currency, reason, Instant.now(), null);
    }
}
