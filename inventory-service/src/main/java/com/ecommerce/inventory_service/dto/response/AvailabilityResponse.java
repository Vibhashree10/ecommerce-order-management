package com.ecommerce.inventory_service.dto.response;

public record AvailabilityResponse(
        Long productId,
        int requestedQuantity,
        int availableQuantity,
        boolean sufficient) {
}