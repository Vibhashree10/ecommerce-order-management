package com.ecommerce.order_service.exception;

/** inventory-service has no reservation for this order (HTTP 404 on confirm / release / get). */
public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(String message) {
        super(message);
    }
}
