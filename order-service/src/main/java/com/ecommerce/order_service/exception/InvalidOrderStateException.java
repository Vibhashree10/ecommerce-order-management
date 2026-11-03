package com.ecommerce.order_service.exception;

/** The requested action is not allowed in the order's current state. Mapped to HTTP 409. */
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
