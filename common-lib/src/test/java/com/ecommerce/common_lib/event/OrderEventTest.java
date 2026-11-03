package com.ecommerce.common_lib.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void jsonRoundTrip_keepsEverything() throws Exception {
        OrderEvent in = new OrderEvent(OrderEvent.ORDER_CONFIRMED, "ORD-1", "c1", "CONFIRMED",
                new BigDecimal("12.50"), "USD", null, Instant.parse("2026-01-01T10:00:00Z"), "a@x.com");

        OrderEvent out = mapper.readValue(mapper.writeValueAsString(in), OrderEvent.class);

        assertThat(out).isEqualTo(in);
    }

    @Test
    void reader_ignoresUnknownFields_evenWhenStrict() throws Exception {
        ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OrderEvent e = strict.readValue("{\"eventType\":\"ORDER_CREATED\",\"orderNumber\":\"ORD-1\",\"somethingNew\":1}", OrderEvent.class);
        assertThat(e.orderNumber()).isEqualTo("ORD-1");
    }

    @Test
    void readsTheJsonOrderServiceProducesToday_withoutCustomerEmail() throws Exception {
        String json = "{\"eventType\":\"ORDER_CREATED\",\"orderNumber\":\"ORD-9\",\"customerId\":\"c1\",\"status\":\"PENDING\","
                + "\"totalAmount\":25.00,\"currency\":\"USD\",\"reason\":null,\"occurredAt\":\"2026-01-01T10:00:00Z\"}";
        OrderEvent e = mapper.readValue(json, OrderEvent.class);
        assertThat(e.customerEmail()).isNull();
        assertThat(e.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void topicsMatchEventTypes() {
        assertThat(Topics.forOrderEvent(OrderEvent.ORDER_CREATED)).isEqualTo("order-created");
        assertThat(Topics.forOrderEvent(OrderEvent.ORDER_CONFIRMED)).isEqualTo("order-confirmed");
        assertThat(Topics.forOrderEvent(OrderEvent.ORDER_CANCELLED)).isEqualTo("order-cancelled");
        assertThat(Topics.forOrderEvent(OrderEvent.ORDER_REJECTED)).isEqualTo("order-rejected");
        assertThat(Topics.forOrderEvent("NOPE")).isNull();
        assertThat(Topics.forOrderEvent(null)).isNull();
    }

    @Test
    void of_setsOccurredAt() {
        assertThat(OrderEvent.of("ORDER_CREATED", "ORD-1", "c", "PENDING", BigDecimal.ONE, "USD", null).occurredAt()).isNotNull();
    }
}
