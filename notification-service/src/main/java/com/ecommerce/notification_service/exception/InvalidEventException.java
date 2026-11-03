package com.ecommerce.notification_service.exception;

/** The event payload is unusable (not JSON, or eventType / orderNumber missing). Mapped to HTTP 400; the Kafka consumer logs and skips such a message. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }
}
