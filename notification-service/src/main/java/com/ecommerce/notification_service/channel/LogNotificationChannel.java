package com.ecommerce.notification_service.channel;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Always on. "Sending" means writing the message to the log (visible in CloudWatch) next to its database row. */
@Slf4j
@Component
public class LogNotificationChannel implements NotificationChannel {

    @Override
    public ChannelType type() {
        return ChannelType.LOG;
    }

    @Override
    public boolean requiresRecipient() {
        return false;
    }

    @Override
    public String resolveRecipient(OrderEventMessage event) {
        return event.customerId();
    }

    @Override
    public void send(Notification n) {
        log.info("NOTIFICATION [{}] order={} to={} subject=\"{}\" body=\"{}\"",
                n.getEventType(), n.getOrderNumber(), n.getRecipient(), n.getSubject(), n.getBody());
    }
}
