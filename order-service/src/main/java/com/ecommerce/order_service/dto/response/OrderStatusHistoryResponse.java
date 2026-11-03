package com.ecommerce.order_service.dto.response;

import com.ecommerce.order_service.entity.OrderStatus;

import java.time.LocalDateTime;

public record OrderStatusHistoryResponse(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String reason,
        LocalDateTime createdAt) {
}
