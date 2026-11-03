package com.ecommerce.order_service.controller;

import com.ecommerce.order_service.dto.request.CancelOrderRequest;
import com.ecommerce.order_service.dto.request.CreateOrderRequest;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.dto.response.OrderStatusHistoryResponse;
import com.ecommerce.order_service.dto.response.PageResponse;
import com.ecommerce.order_service.entity.OrderStatus;
import com.ecommerce.order_service.service.OrderService;
import com.ecommerce.order_service.util.Constants;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Orders API (context path /api is added by Spring):
 *   POST /orders                      place an order; optional header Idempotency-Key. 201 when decided (STOCK_RESERVED / REJECTED),
 *                                     202 when still PENDING (inventory-service unreachable, retried automatically)
 *   GET  /orders/{orderNumber}        one order
 *   GET  /orders?customerId=&status=  paged list, newest first
 *   POST /orders/{orderNumber}/confirm  STOCK_RESERVED -> CONFIRMED
 *   POST /orders/{orderNumber}/cancel   -> CANCELLED (optional body {"reason": "..."})
 *   GET  /orders/{orderNumber}/history  audit trail of status changes
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = Constants.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.createOrder(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{orderNumber}")
                .buildAndExpand(created.orderNumber()).toUri();
        HttpStatus status = created.status() == OrderStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).location(location).body(created);
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse get(@PathVariable("orderNumber") String orderNumber) {
        return orderService.getOrder(orderNumber);
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "status", required = false) OrderStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return orderService.listOrders(customerId, status, page, size);
    }

    @PostMapping("/{orderNumber}/confirm")
    public OrderResponse confirm(@PathVariable("orderNumber") String orderNumber) {
        return orderService.confirmOrder(orderNumber);
    }

    @PostMapping("/{orderNumber}/cancel")
    public OrderResponse cancel(@PathVariable("orderNumber") String orderNumber,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return orderService.cancelOrder(orderNumber, request == null ? null : request.reason());
    }

    @GetMapping("/{orderNumber}/history")
    public List<OrderStatusHistoryResponse> history(@PathVariable("orderNumber") String orderNumber) {
        return orderService.getHistory(orderNumber);
    }
}
