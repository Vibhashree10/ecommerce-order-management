package com.ecommerce.order_service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes domain events AFTER the surrounding database transaction commits, so a broker problem can never
 * roll back or hang an order change, and an event is never sent for a transaction that rolled back.
 * Limitation (Phase 2): if the process dies between commit and publish the event is lost (fix: Transactional Outbox).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public void publishAfterCommit(String topic, String key, OrderEvent event) {
        final String payload = serialize(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(topic, key, payload);
                }
            });
        } else {
            eventPublisher.publish(topic, key, payload);
        }
    }

    private String serialize(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Could not serialize event {}: {}", event, e.toString());
            return String.valueOf(event);
        }
    }
}
