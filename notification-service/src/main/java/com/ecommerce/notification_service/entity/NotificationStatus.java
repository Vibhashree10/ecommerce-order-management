package com.ecommerce.notification_service.entity;

/**
 * PENDING  created, not sent yet (or being sent)
 * SENT     delivered to the channel
 * FAILED   the channel threw an error; the retry job tries again until notifications.max-attempts
 * SKIPPED  nothing to send to (for example an EMAIL notification without a recipient)
 */
public enum NotificationStatus {
    PENDING, SENT, FAILED, SKIPPED
}
