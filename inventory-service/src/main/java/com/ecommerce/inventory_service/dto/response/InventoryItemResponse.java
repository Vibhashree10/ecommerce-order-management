package com.ecommerce.inventory_service.dto.response;

import java.time.LocalDateTime;

public record InventoryItemResponse(
        Long productId,
        int availableQuantity,
        int reservedQuantity,
        int reorderLevel,
        boolean lowStock,
        LocalDateTime updatedAt) {
}