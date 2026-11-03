package com.ecommerce.order_service.event;

/**
 * Abstraction over the messaging system (Strategy pattern).
 * DIFFERENCE vs product-service: this version takes a message KEY. Kafka guarantees ordering
 * only within a partition, and records with the same key land in the same partition, so
 * all events of one order (or one product) stay in order.
 */
public interface EventPublisher {
    void publish(String topic, String key, String message);
}
