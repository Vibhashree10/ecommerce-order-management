package com.ecommerce.notification_service.exception;

/** The action is not allowed in the notification's current state (for example retry of a SENT notification). Mapped to HTTP 409. */
public class InvalidNotificationStateException extends RuntimeException {
    public InvalidNotificationStateException(String message) {
        super(message);
    }
}
