package com.ecommerce.inventory_service.entity;

/**
 * Reservation lifecycle:
 * PENDING -> CONFIRMED (order paid, stock committed)
 * PENDING -> RELEASED  (order cancelled / payment failed: compensation)
 * PENDING -> EXPIRED   (nobody confirmed within the TTL)
 */
public enum ReservationStatus {
    PENDING, CONFIRMED, RELEASED, EXPIRED
}