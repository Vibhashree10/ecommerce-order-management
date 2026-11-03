package com.ecommerce.notification_service.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The order event as order-service publishes it (topics order-created / -confirmed / -cancelled / -rejected).
 * This service keeps its OWN copy of the shape (tolerant reader): unknown fields are ignored, so order-service can add
 * fields without breaking this consumer. customerEmail is optional and not sent by order-service yet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventMessage(
        @NotBlank String eventType,
        @NotBlank String orderNumber,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String reason,
        Instant occurredAt,
        String customerEmail) {
}
