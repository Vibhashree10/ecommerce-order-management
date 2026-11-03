package com.ecommerce.notification_service.consumer;

import com.ecommerce.notification_service.exception.InvalidEventException;
import com.ecommerce.notification_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point. Active only when notifications.kafka.enabled=true.
 *  - An unusable message (not JSON, no orderNumber) is logged and SKIPPED: a poison message must not block the partition.
 *  - Any other exception (for example the database is down) is rethrown: the container error handler retries, then moves on
 *    (see KafkaConsumerConfig). Phase 2: a dead-letter topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notifications.kafka.enabled", havingValue = "true")
public class OrderEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "#{'${notifications.kafka.topics}'.split(',')}")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.debug("Received {} partition={} offset={} key={}", record.topic(), record.partition(), record.offset(), record.key());
        try {
            notificationService.handleEvent(record.value());
        } catch (InvalidEventException e) {
            log.warn("Skipping unusable message on {} offset {}: {}", record.topic(), record.offset(), e.getMessage());
        }
    }
}
