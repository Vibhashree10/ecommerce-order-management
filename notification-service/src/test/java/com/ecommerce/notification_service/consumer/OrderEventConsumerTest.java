package com.ecommerce.notification_service.consumer;

import com.ecommerce.notification_service.exception.InvalidEventException;
import com.ecommerce.notification_service.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEventConsumerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final OrderEventConsumer consumer = new OrderEventConsumer(service);

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("order-created", 0, 5L, "ORD-1", value);
    }

    @Test
    void passesThePayloadToTheService() {
        consumer.onMessage(record("{}"));
        verify(service).handleEvent("{}");
    }

    @Test
    void unusableMessage_isSkipped() {
        when(service.handleEvent("bad")).thenThrow(new InvalidEventException("bad"));
        assertThatCode(() -> consumer.onMessage(record("bad"))).doesNotThrowAnyException();
    }

    @Test
    void infrastructureError_propagatesSoTheErrorHandlerRetries() {
        when(service.handleEvent("{}")).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> consumer.onMessage(record("{}"))).isInstanceOf(IllegalStateException.class);
    }
}
