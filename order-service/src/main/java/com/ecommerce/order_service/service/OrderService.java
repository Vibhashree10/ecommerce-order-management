package com.ecommerce.order_service.service;

import com.ecommerce.order_service.dto.request.CreateOrderRequest;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.dto.response.OrderStatusHistoryResponse;
import com.ecommerce.order_service.dto.response.PageResponse;
import com.ecommerce.order_service.entity.OrderStatus;

import java.util.List;

public interface OrderService {

    /** Creates the order and tries to reserve stock. Result status: STOCK_RESERVED, REJECTED, or PENDING (inventory unreachable, retried by the recovery job). */
    OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey);

    OrderResponse getOrder(String orderNumber);

    PageResponse<OrderResponse> listOrders(String customerId, OrderStatus status, int page, int size);

    /** STOCK_RESERVED -> CONFIRMED (commits the stock). Idempotent when already CONFIRMED. */
    OrderResponse confirmOrder(String orderNumber);

    /** PENDING / STOCK_RESERVED -> CANCELLED (releases the stock). Idempotent when already CANCELLED. */
    OrderResponse cancelOrder(String orderNumber, String reason);

    List<OrderStatusHistoryResponse> getHistory(String orderNumber);

    /** Recovery job: retry the stock reservation of an order stuck in PENDING (gives up after orders.pending-max-age-minutes). */
    void retryPendingOrder(String orderNumber);

    /** Recovery job: align an order stuck in STOCK_RESERVED with the state of its reservation in inventory-service. */
    void reconcileReservedOrder(String orderNumber);
}
