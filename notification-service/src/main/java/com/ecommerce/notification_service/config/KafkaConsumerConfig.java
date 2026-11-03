package com.ecommerce.notification_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Retries a failing message 3 times, 2 seconds apart, then logs it and moves on (so one bad record cannot stall the topic forever). */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "notifications.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error("Giving up on {} offset {} after retries: {}", record.topic(), record.offset(), ex.toString()),
                new FixedBackOff(2000L, 3L));
        return handler;
    }
}
