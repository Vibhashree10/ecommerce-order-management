package com.ecommerce.notification_service.util;

public final class Constants {

    private Constants() {
    }

    // Event types written by order-service in OrderEvent.eventType
    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String EVENT_ORDER_REJECTED = "ORDER_REJECTED";

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_ERROR_LENGTH = 255;
    public static final int MAX_BODY_LENGTH = 2000;
}
