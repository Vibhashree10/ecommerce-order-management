package com.ecommerce.inventory_service.dto.response;

import com.ecommerce.inventory_service.entity.MovementType;

import java.time.LocalDateTime;

public record StockMovementResponse(
        Long productId,
        MovementType type,
        int quantityDelta,
        String reference,
        String reason,
        LocalDateTime createdAt) {
}