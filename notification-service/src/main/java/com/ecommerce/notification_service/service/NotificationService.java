package com.ecommerce.notification_service.service;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.dto.response.PageResponse;
import com.ecommerce.notification_service.entity.NotificationStatus;

import java.util.List;

public interface NotificationService {

    /** Entry point for the Kafka consumer: parses the JSON payload, then handles it. Throws InvalidEventException for unusable payloads. */
    List<NotificationResponse> handleEvent(String payloadJson);

    /** Creates one notification per active channel and sends it. Safe to call twice for the same event: the second call creates nothing. */
    List<NotificationResponse> handleEvent(OrderEventMessage event);

    NotificationResponse get(Long id);

    PageResponse<NotificationResponse> list(String orderNumber, String customerId, NotificationStatus status, int page, int size);

    /** Sends a FAILED notification again (HTTP 409 if it is in another state). */
    NotificationResponse retry(Long id);

    /** Used by the retry job: sends a FAILED notification again; silently ignores one that is no longer FAILED. */
    void retryIfFailed(Long id);
}
