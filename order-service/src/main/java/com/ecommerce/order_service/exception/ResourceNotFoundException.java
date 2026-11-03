package com.ecommerce.order_service.exception;

/** Order (or other resource) not found. Mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
