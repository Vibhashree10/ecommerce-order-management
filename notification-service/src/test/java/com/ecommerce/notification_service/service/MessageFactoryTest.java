package com.ecommerce.notification_service.service;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFactoryTest {

    private final MessageFactory factory = new MessageFactory();

    private OrderEventMessage event(String type, String reason) {
        return new OrderEventMessage(type, "ORD-1", "cust-9", "X", new BigDecimal("12.50"), "USD", reason, null, null);
    }

    @Test
    void createdAndConfirmed_mentionTheOrderNumber() {
        assertThat(factory.build(event("ORDER_CREATED", null)).orElseThrow().subject()).contains("ORD-1");
        assertThat(factory.build(event("ORDER_CONFIRMED", null)).orElseThrow().body()).contains("ORD-1", "cust-9");
    }

    @Test
    void cancelledAndRejected_includeTheReason() {
        assertThat(factory.build(event("ORDER_CANCELLED", "out of stock")).orElseThrow().body()).contains("out of stock");
        assertThat(factory.build(event("ORDER_REJECTED", "payment failed")).orElseThrow().body()).contains("payment failed");
    }

    @Test
    void unknownEventType_producesNoMessage() {
        assertThat(factory.build(event("SOMETHING_ELSE", null))).isEmpty();
    }
}
