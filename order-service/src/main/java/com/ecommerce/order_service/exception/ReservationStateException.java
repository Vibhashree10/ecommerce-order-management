package com.ecommerce.order_service.exception;

/** inventory-service cannot apply confirm / release because the reservation is in another state, for example already expired (HTTP 409). */
public class ReservationStateException extends RuntimeException {
    public ReservationStateException(String message) {
        super(message);
    }
}
