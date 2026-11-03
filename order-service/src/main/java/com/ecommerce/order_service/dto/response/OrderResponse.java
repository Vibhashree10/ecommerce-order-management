package com.ecommerce.order_service.dto.response;

import com.ecommerce.order_service.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        String orderNumber,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderItemResponse> items) {
}
