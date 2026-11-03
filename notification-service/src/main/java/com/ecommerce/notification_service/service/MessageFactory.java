package com.ecommerce.notification_service.service;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.util.Constants;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** Builds the subject and text for each order event. Unknown event types produce no message (the event is ignored). */
@Component
public class MessageFactory {

    public record Message(String subject, String body) {
    }

    public Optional<Message> build(OrderEventMessage e) {
        String order = e.orderNumber();
        String amount = amount(e);
        String who = e.customerId() == null || e.customerId().isBlank() ? "customer" : e.customerId();
        return switch (e.eventType()) {
            case Constants.EVENT_ORDER_CREATED -> Optional.of(new Message(
                    "We received your order " + order,
                    "Hi " + who + ", we received your order " + order + (amount.isEmpty() ? "" : " for " + amount)
                            + ". We are checking the stock and will confirm shortly."));
            case Constants.EVENT_ORDER_CONFIRMED -> Optional.of(new Message(
                    "Your order " + order + " is confirmed",
                    "Hi " + who + ", your order " + order + (amount.isEmpty() ? "" : " for " + amount)
                            + " is confirmed. Thank you for shopping with us."));
            case Constants.EVENT_ORDER_CANCELLED -> Optional.of(new Message(
                    "Your order " + order + " was cancelled",
                    "Hi " + who + ", your order " + order + " was cancelled." + reason(e)));
            case Constants.EVENT_ORDER_REJECTED -> Optional.of(new Message(
                    "We could not place your order " + order,
                    "Hi " + who + ", we could not place your order " + order + "." + reason(e)));
            default -> Optional.empty();
        };
    }

    private static String amount(OrderEventMessage e) {
        if (e.totalAmount() == null) {
            return "";
        }
        String text = String.format(Locale.US, "%.2f", e.totalAmount());
        return e.currency() == null || e.currency().isBlank() ? text : text + " " + e.currency();
    }

    private static String reason(OrderEventMessage e) {
        return e.reason() == null || e.reason().isBlank() ? "" : " Reason: " + e.reason() + ".";
    }
}
