package com.ecommerce.inventory_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** orderId doubles as the idempotency key: reserving the same orderId twice never reserves twice. */
public record ReserveStockRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotEmpty @Valid List<ReservationItemRequest> items) {
}