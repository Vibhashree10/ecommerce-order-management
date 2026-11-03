package com.ecommerce.notification_service.dto.response;

import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.NotificationStatus;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        ChannelType channel,
        String eventType,
        String orderNumber,
        String customerId,
        String recipient,
        String subject,
        String body,
        NotificationStatus status,
        int attempts,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime sentAt) {
}
