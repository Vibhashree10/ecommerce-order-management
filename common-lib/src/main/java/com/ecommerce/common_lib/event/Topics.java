package com.ecommerce.common_lib.event;

/** Kafka topic names. Order events use the order number as the record key. */
public final class Topics {

    public static final String ORDER_CREATED = "order-created";
    public static final String ORDER_CONFIRMED = "order-confirmed";
    public static final String ORDER_CANCELLED = "order-cancelled";
    public static final String ORDER_REJECTED = "order-rejected";

    private Topics() {
    }

    /** Topic that carries the given OrderEvent type, or null for an unknown type. */
    public static String forOrderEvent(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case OrderEvent.ORDER_CREATED -> ORDER_CREATED;
            case OrderEvent.ORDER_CONFIRMED -> ORDER_CONFIRMED;
            case OrderEvent.ORDER_CANCELLED -> ORDER_CANCELLED;
            case OrderEvent.ORDER_REJECTED -> ORDER_REJECTED;
            default -> null;
        };
    }
}
