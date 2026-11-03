package com.ecommerce.order_service.exception;

/** A downstream service (inventory-service, product-service) failed or is unreachable. Mapped to HTTP 503. */
public class ExternalServiceException extends RuntimeException {
    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
