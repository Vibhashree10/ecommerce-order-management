package com.ecommerce.notification_service.channel;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;

/**
 * Strategy: one implementation per way of telling the customer. NotificationServiceImpl runs every channel bean that is
 * present, so a new channel (SMS, push) is a new class and nothing else.
 */
public interface NotificationChannel {

    ChannelType type();

    /** true when a notification without a recipient cannot be sent (it is stored as SKIPPED instead). */
    boolean requiresRecipient();

    /** Who to send to for this event; null when unknown. */
    String resolveRecipient(OrderEventMessage event);

    /** Deliver the notification. Throw any exception to mark the attempt as FAILED (the retry job tries again). */
    void send(Notification notification) throws Exception;
}
