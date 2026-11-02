package com.ecommerce.inventory_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** delta > 0 adds stock (e.g. goods received), delta < 0 removes it (e.g. damaged). */
public record AdjustStockRequest(
        @NotNull Integer delta,
        @NotBlank @Size(max = 255) String reason) {
}