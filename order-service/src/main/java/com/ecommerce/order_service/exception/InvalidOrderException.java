package com.ecommerce.order_service.exception;

/** The order request itself is wrong (unknown/inactive product, missing price, too many items). Mapped to HTTP 400. */
public class InvalidOrderException extends RuntimeException {
    public InvalidOrderException(String message) {
        super(message);
    }
}
