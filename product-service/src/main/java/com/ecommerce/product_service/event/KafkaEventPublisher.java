package com.ecommerce.product_service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Active only when app.events.publisher=kafka. Never lets a broker problem break the API call. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void publish(String topic, String message) {
        try {
            kafkaTemplate.send(topic, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish FAILED topic={} : {}", topic, ex.toString());
                } else {
                    log.info("Kafka published topic={} partition={} offset={}",
                            topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            log.error("Kafka publish threw topic={} : {}", topic, e.toString());
        }
    }
}