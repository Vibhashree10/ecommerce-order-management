package com.ecommerce.notification_service.mapper;

import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.entity.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getChannel(), n.getEventType(), n.getOrderNumber(), n.getCustomerId(),
                n.getRecipient(), n.getSubject(), n.getBody(), n.getStatus(), n.getAttempts(), n.getLastError(),
                n.getCreatedAt(), n.getSentAt());
    }
}
