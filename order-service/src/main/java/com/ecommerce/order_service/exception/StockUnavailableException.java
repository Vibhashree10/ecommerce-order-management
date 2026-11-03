package com.ecommerce.order_service.exception;

/** inventory-service refused the reservation: not enough stock, or the product is not stocked. Business outcome, mapped to a REJECTED order (HTTP 409 if it ever escapes). */
public class StockUnavailableException extends RuntimeException {
    public StockUnavailableException(String message) {
        super(message);
    }
}
