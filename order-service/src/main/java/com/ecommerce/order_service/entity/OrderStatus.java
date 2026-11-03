package com.ecommerce.order_service.entity;

/**
 * Order state machine (Saga state):
 *   PENDING        created, stock reservation not yet answered by inventory-service
 *   STOCK_RESERVED stock is held for this order (inventory reservation is PENDING there, TTL 15 min)
 *   CONFIRMED      stock committed (terminal)
 *   CANCELLED      cancelled by the customer, or the reservation expired / could not be completed (terminal)
 *   REJECTED       not enough stock (terminal)
 */
public enum OrderStatus {
    PENDING, STOCK_RESERVED, CONFIRMED, CANCELLED, REJECTED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == REJECTED;
    }
}
