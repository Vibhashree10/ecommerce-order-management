package com.ecommerce.order_service.mapper;

import com.ecommerce.order_service.dto.response.OrderItemResponse;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.dto.response.OrderStatusHistoryResponse;
import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderItem;
import com.ecommerce.order_service.entity.OrderStatusHistory;
import org.springframework.stereotype.Component;

/** Entity -> DTO. Entities never leave the service layer (open-in-view is off, and lazy fields must not leak). */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream().map(this::toItemResponse).toList());
    }

    public OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getSku(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal());
    }

    public OrderStatusHistoryResponse toHistoryResponse(OrderStatusHistory row) {
        return new OrderStatusHistoryResponse(row.getFromStatus(), row.getToStatus(), row.getReason(), row.getCreatedAt());
    }
}
