package com.ecommerce.order_service.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default publisher: no infrastructure needed. Active unless app.events.publisher=kafka. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "logging", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    @Override
    public void publish(String topic, String key, String message) {
        log.info("EVENT [{}] key={} {}", topic, key, message);
    }
}
