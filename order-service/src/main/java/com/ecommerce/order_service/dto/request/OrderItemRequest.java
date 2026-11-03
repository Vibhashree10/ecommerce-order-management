package com.ecommerce.order_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * unitPrice is optional: when product lookup is enabled the catalog price always wins (a client must not choose its price).
 * It is only used as the price when product lookup is disabled (local / early deployments).
 */
public record OrderItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Min(1) @Max(1000) Integer quantity,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
}
