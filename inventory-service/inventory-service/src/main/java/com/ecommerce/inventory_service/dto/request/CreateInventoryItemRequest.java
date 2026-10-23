package com.ecommerce.inventory_service.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateInventoryItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Min(0) Integer quantity,
        @Min(0) Integer reorderLevel) {
}