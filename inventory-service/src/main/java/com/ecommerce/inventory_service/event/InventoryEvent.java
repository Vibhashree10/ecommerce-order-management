package com.ecommerce.inventory_service.event;

import java.time.Instant;

/** Payload of every event this service emits (serialized to JSON). */
public record InventoryEvent(
        String eventType,
        String orderId,
        Long productId,
        Integer quantity,
        Integer availableQuantity,
        Instant occurredAt) {

    /** Factory method keeps event construction consistent (always stamps occurredAt). */
    public static InventoryEvent of(String eventType, String orderId, Long productId,
                                    Integer quantity, Integer availableQuantity) {
        return new InventoryEvent(eventType, orderId, productId, quantity, availableQuantity, Instant.now());
    }
}