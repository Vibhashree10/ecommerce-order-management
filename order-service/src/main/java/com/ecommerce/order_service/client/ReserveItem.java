package com.ecommerce.order_service.client;

/** One line of inventory-service's POST /inventory/reservations body. */
public record ReserveItem(Long productId, Integer quantity) {
}
