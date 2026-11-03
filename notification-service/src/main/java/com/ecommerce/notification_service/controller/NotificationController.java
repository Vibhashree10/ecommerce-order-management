package com.ecommerce.notification_service.controller;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.dto.response.PageResponse;
import com.ecommerce.notification_service.entity.NotificationStatus;
import com.ecommerce.notification_service.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Notifications API (context path /api is added by Spring):
 *   GET  /notifications?orderNumber=&customerId=&status=&page=&size=   paged list, newest first
 *   GET  /notifications/{id}                                           one notification
 *   POST /notifications/{id}/retry                                     send a FAILED notification again
 *   POST /notifications/events                                         handle an order event by HTTP (same code path as Kafka);
 *                                                                      for testing without Kafka and for manual replays
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(value = "orderNumber", required = false) String orderNumber,
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "status", required = false) NotificationStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return notificationService.list(orderNumber, customerId, status, page, size);
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable("id") Long id) {
        return notificationService.get(id);
    }

    @PostMapping("/{id}/retry")
    public NotificationResponse retry(@PathVariable("id") Long id) {
        return notificationService.retry(id);
    }

    @PostMapping("/events")
    public ResponseEntity<List<NotificationResponse>> handleEvent(@Valid @RequestBody OrderEventMessage event) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(notificationService.handleEvent(event));
    }
}
