package com.ecommerce.order_service.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Payload of every event this service emits (serialized to JSON). */
public record OrderEvent(
        String eventType,
        String orderNumber,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String reason,
        Instant occurredAt) {

    public static OrderEvent of(String eventType, String orderNumber, String customerId, String status,
                                BigDecimal totalAmount, String currency, String reason) {
        return new OrderEvent(eventType, orderNumber, customerId, status, totalAmount, currency, reason, Instant.now());
    }
}
