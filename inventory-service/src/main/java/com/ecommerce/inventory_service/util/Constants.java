package com.ecommerce.inventory_service.util;

public final class Constants {

    private Constants() {
    }

    // Kafka topics published by this service
    public static final String TOPIC_STOCK_UPDATED = "inventory-updated";
    public static final String TOPIC_STOCK_RESERVED = "inventory-reserved";
    public static final String TOPIC_STOCK_CONFIRMED = "inventory-confirmed";
    public static final String TOPIC_STOCK_RELEASED = "inventory-released";
    public static final String TOPIC_LOW_STOCK = "inventory-low-stock";

    public static final int MAX_PAGE_SIZE = 100;
}