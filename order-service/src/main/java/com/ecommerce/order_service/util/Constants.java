package com.ecommerce.order_service.util;

public final class Constants {

    private Constants() {
    }

    // Kafka topics published by this service
    public static final String TOPIC_ORDER_CREATED = "order-created";
    public static final String TOPIC_ORDER_CONFIRMED = "order-confirmed";
    public static final String TOPIC_ORDER_CANCELLED = "order-cancelled";
    public static final String TOPIC_ORDER_REJECTED = "order-rejected";

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_REASON_LENGTH = 255;
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
}
