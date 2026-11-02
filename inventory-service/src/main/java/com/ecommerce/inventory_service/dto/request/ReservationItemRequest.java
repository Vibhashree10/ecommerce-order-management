package com.ecommerce.inventory_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {
}