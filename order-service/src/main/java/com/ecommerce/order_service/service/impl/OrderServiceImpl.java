package com.ecommerce.order_service.service.impl;

import com.ecommerce.order_service.client.InventoryClient;
import com.ecommerce.order_service.client.ProductClient;
import com.ecommerce.order_service.client.ProductInfo;
import com.ecommerce.order_service.client.ReservationInfo;
import com.ecommerce.order_service.client.ReserveItem;
import com.ecommerce.order_service.dto.request.CreateOrderRequest;
import com.ecommerce.order_service.dto.request.OrderItemRequest;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.dto.response.OrderStatusHistoryResponse;
import com.ecommerce.order_service.dto.response.PageResponse;
import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderItem;
import com.ecommerce.order_service.entity.OrderStatus;
import com.ecommerce.order_service.entity.OrderStatusHistory;
import com.ecommerce.order_service.event.OrderEvent;
import com.ecommerce.order_service.event.OrderEventPublisher;
import com.ecommerce.order_service.exception.InvalidOrderException;
import com.ecommerce.order_service.exception.InvalidOrderStateException;
import com.ecommerce.order_service.exception.ReservationNotFoundException;
import com.ecommerce.order_service.exception.ReservationStateException;
import com.ecommerce.order_service.exception.ResourceNotFoundException;
import com.ecommerce.order_service.exception.StockUnavailableException;
import com.ecommerce.order_service.mapper.OrderMapper;
import com.ecommerce.order_service.repository.OrderRepository;
import com.ecommerce.order_service.repository.OrderStatusHistoryRepository;
import com.ecommerce.order_service.service.OrderService;
import com.ecommerce.order_service.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Saga ORCHESTRATOR. order-service drives inventory-service step by step and compensates on failure:
 *
 *   create  : save order PENDING --(remote)--> reserve stock --> STOCK_RESERVED | REJECTED | stays PENDING
 *   confirm : (remote) confirm reservation --> STOCK_RESERVED -> CONFIRMED
 *   cancel  : (remote) release reservation --> PENDING / STOCK_RESERVED -> CANCELLED      (compensation)
 *
 * Design rules (each one answers a real failure mode):
 *  1. NO database transaction is open during a remote call. A slow inventory-service must not hold a DB connection
 *     (pool size is 5 on AWS). Every DB write is a short TransactionTemplate block.
 *  2. Status changes are compare-and-set UPDATEs (OrderRepository.transition). When confirm, cancel and the recovery job
 *     race on one order, exactly one wins; the others see 0 rows and do not apply side effects.
 *  3. Remote calls are idempotent on inventory's side (keyed by order number), so "call again" is always a safe recovery.
 *  4. If inventory-service is unreachable the order stays PENDING and OrderRecoveryJob retries; nothing is lost.
 *  5. Events are published only after the DB transaction commits (OrderEventPublisher).
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private enum ReserveOutcome { RESERVED, REJECTED, FAILED }

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final InventoryClient inventoryClient;
    private final ProductClient productClient;
    private final OrderMapper mapper;
    private final OrderEventPublisher events;
    private final TransactionTemplate tx;
    private final String defaultCurrency;
    private final int maxItemsPerOrder;
    private final long pendingMaxAgeMinutes;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OrderStatusHistoryRepository historyRepository,
                            InventoryClient inventoryClient,
                            ProductClient productClient,
                            OrderMapper mapper,
                            OrderEventPublisher events,
                            TransactionTemplate tx,
                            @Value("${orders.default-currency:USD}") String defaultCurrency,
                            @Value("${orders.max-items-per-order:50}") int maxItemsPerOrder,
                            @Value("${orders.pending-max-age-minutes:10}") long pendingMaxAgeMinutes) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.inventoryClient = inventoryClient;
        this.productClient = productClient;
        this.mapper = mapper;
        this.events = events;
        this.tx = tx;
        this.defaultCurrency = defaultCurrency;
        this.maxItemsPerOrder = maxItemsPerOrder;
        this.pendingMaxAgeMinutes = pendingMaxAgeMinutes;
    }

    // ------------------------------------------------------------------ create

    @Override
    public OrderResponse createOrder(CreateOrderRequest request, String rawIdempotencyKey) {
        String key = normalizeKey(rawIdempotencyKey);
        String customerId = request.customerId().trim();

        if (key != null) {
            Optional<Order> existing = orderRepository.findByCustomerIdAndIdempotencyKey(customerId, key);
            if (existing.isPresent()) {
                log.info("Idempotent replay of create order: customer={} key={} -> {}", customerId, key,
                        existing.get().getOrderNumber());
                return mapper.toResponse(existing.get());
            }
        }

        Order order = buildOrder(request, customerId, key);

        Order saved;
        try {
            saved = Objects.requireNonNull(tx.execute(status -> persistNew(order)));
        } catch (DataIntegrityViolationException e) {
            // two identical requests raced past the lookup above; the unique (customer_id, idempotency_key) let one win
            if (key != null) {
                Optional<Order> winner = orderRepository.findByCustomerIdAndIdempotencyKey(customerId, key);
                if (winner.isPresent()) {
                    return mapper.toResponse(winner.get());
                }
            }
            throw e;
        }

        reserveStock(saved);
        return mapper.toResponse(reload(saved.getOrderNumber()));
    }

    private Order persistNew(Order order) {
        Order saved = orderRepository.save(order);
        historyRepository.save(OrderStatusHistory.of(saved.getId(), null, OrderStatus.PENDING, "Order created"));
        events.publishAfterCommit(Constants.TOPIC_ORDER_CREATED, saved.getOrderNumber(), eventFor("ORDER_CREATED", saved, OrderStatus.PENDING, null));
        return saved;
    }

    private Order buildOrder(CreateOrderRequest request, String customerId, String key) {
        List<OrderItemRequest> lines = request.items();
        if (lines.size() > maxItemsPerOrder) {
            throw new InvalidOrderException("An order can have at most " + maxItemsPerOrder + " lines");
        }
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest line : lines) {
            if (!seen.add(line.productId())) {
                throw new InvalidOrderException("Product " + line.productId() + " appears more than once; send one line with the total quantity");
            }
        }

        String currency = request.currency() == null || request.currency().isBlank()
                ? defaultCurrency : request.currency().trim().toUpperCase(Locale.ROOT);
        Order order = Order.create(newOrderNumber(), customerId, currency, key);

        for (OrderItemRequest line : lines) {
            order.addItem(toOrderItem(line));
        }
        return order;
    }

    private OrderItem toOrderItem(OrderItemRequest line) {
        if (productClient.isEnabled()) {
            ProductInfo product = productClient.find(line.productId())
                    .orElseThrow(() -> new InvalidOrderException("Product " + line.productId() + " does not exist"));
            if (!product.isActive()) {
                throw new InvalidOrderException("Product " + line.productId() + " is not available for sale");
            }
            if (product.price() == null) {
                throw new InvalidOrderException("Product " + line.productId() + " has no price");
            }
            // catalog price always wins: a client must not be able to choose its own price
            return OrderItem.of(line.productId(), product.sku(), product.name(), product.price(), line.quantity());
        }
        if (line.unitPrice() == null) {
            throw new InvalidOrderException("unitPrice is required for product " + line.productId()
                    + " because product lookup is disabled");
        }
        return OrderItem.of(line.productId(), null, null, line.unitPrice(), line.quantity());
    }

    private static String newOrderNumber() {
        return "ORD-" + LocalDate.now().format(DAY) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 100) {
            throw new InvalidOrderException(Constants.IDEMPOTENCY_HEADER + " must be at most 100 characters");
        }
        return trimmed;
    }

    /** Saga step 1: reserve stock for a PENDING order. Never throws for downstream trouble; the order simply stays PENDING. */
    private ReserveOutcome reserveStock(Order order) {
        try {
            // sorted so concurrent orders always ask inventory for products in the same order (avoids lock-order deadlocks there)
            List<ReserveItem> items = order.getItems().stream()
                    .sorted((a, b) -> a.getProductId().compareTo(b.getProductId()))
                    .map(i -> new ReserveItem(i.getProductId(), i.getQuantity()))
                    .toList();
            inventoryClient.reserve(order.getOrderNumber(), items);
        } catch (StockUnavailableException e) {
            moveTo(order, OrderStatus.PENDING, OrderStatus.REJECTED, e.getMessage());
            return ReserveOutcome.REJECTED;
        } catch (RuntimeException e) {
            log.warn("Stock reservation for order {} did not complete ({}); it stays PENDING and will be retried",
                    order.getOrderNumber(), e.toString());
            return ReserveOutcome.FAILED;
        }
        moveTo(order, OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, null);
        return ReserveOutcome.RESERVED;
    }

    // ------------------------------------------------------------------ read

    @Override
    public OrderResponse getOrder(String orderNumber) {
        return mapper.toResponse(findOrThrow(orderNumber));
    }

    @Override
    public PageResponse<OrderResponse> listOrders(String customerId, OrderStatus statusFilter, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), Constants.MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        String customer = customerId == null || customerId.isBlank() ? null : customerId.trim();

        // query and mapping share one read transaction: open-in-view is off, so the lines (lazy) must be read before it ends
        return tx.execute(status -> {
            Page<Order> result;
            if (customer != null && statusFilter != null) {
                result = orderRepository.findByCustomerIdAndStatus(customer, statusFilter, pageable);
            } else if (customer != null) {
                result = orderRepository.findByCustomerId(customer, pageable);
            } else if (statusFilter != null) {
                result = orderRepository.findByStatus(statusFilter, pageable);
            } else {
                result = orderRepository.findAll(pageable);
            }
            return PageResponse.from(result, mapper::toResponse);
        });
    }

    @Override
    public List<OrderStatusHistoryResponse> getHistory(String orderNumber) {
        Order order = findOrThrow(orderNumber);
        return historyRepository.findByOrderIdOrderByIdAsc(order.getId()).stream().map(mapper::toHistoryResponse).toList();
    }

    // ------------------------------------------------------------------ confirm

    @Override
    public OrderResponse confirmOrder(String orderNumber) {
        Order order = findOrThrow(orderNumber);
        switch (order.getStatus()) {
            case CONFIRMED:
                return mapper.toResponse(order);
            case STOCK_RESERVED:
                break;
            case PENDING:
                throw new InvalidOrderStateException("Order " + orderNumber + " is still PENDING (stock reservation not completed yet); retry shortly");
            default:
                throw new InvalidOrderStateException("Order " + orderNumber + " is " + order.getStatus() + " and cannot be confirmed");
        }

        try {
            inventoryClient.confirm(orderNumber);
        } catch (ReservationStateException | ReservationNotFoundException e) {
            // inventory refused: the reservation may already be confirmed (earlier attempt whose reply was lost) or it expired / was released
            Optional<ReservationInfo> reservation = inventoryClient.get(orderNumber);
            if (reservation.isEmpty() || !reservation.get().isConfirmed()) {
                moveTo(order, OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED, "Stock reservation expired or was released before confirmation");
                throw new InvalidOrderStateException("The stock reservation for order " + orderNumber + " is no longer valid; the order was cancelled");
            }
        }

        if (!moveTo(order, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED, null)) {
            Order current = reload(orderNumber);
            if (current.getStatus() != OrderStatus.CONFIRMED) {
                log.error("Order {} is {} although inventory confirmed its stock; needs manual review", orderNumber, current.getStatus());
                throw new InvalidOrderStateException("Order " + orderNumber + " changed concurrently and is now " + current.getStatus());
            }
        }
        return mapper.toResponse(reload(orderNumber));
    }

    // ------------------------------------------------------------------ cancel

    @Override
    public OrderResponse cancelOrder(String orderNumber, String reason) {
        String why = truncate(reason == null || reason.isBlank() ? "Cancelled by customer" : reason.trim());

        for (int attempt = 0; attempt < 2; attempt++) {
            Order order = findOrThrow(orderNumber);
            OrderStatus from = order.getStatus();
            if (from == OrderStatus.CANCELLED) {
                return mapper.toResponse(order);
            }
            if (from != OrderStatus.PENDING && from != OrderStatus.STOCK_RESERVED) {
                throw new InvalidOrderStateException("Order " + orderNumber + " is " + from + " and cannot be cancelled");
            }

            if (!releaseStock(orderNumber)) {
                // inventory says the stock is already committed: the order really is confirmed, fix our state and refuse
                moveTo(order, from, OrderStatus.CONFIRMED, "Aligned with inventory-service: reservation was already confirmed");
                throw new InvalidOrderStateException("Order " + orderNumber + " is already confirmed and cannot be cancelled");
            }
            if (moveTo(order, from, OrderStatus.CANCELLED, why)) {
                return mapper.toResponse(reload(orderNumber));
            }
            // somebody else moved the order (for example reservation finished while we cancelled a PENDING order): look again once
        }
        throw new InvalidOrderStateException("Order " + orderNumber + " changed concurrently; please retry");
    }

    /**
     * Saga compensation. Returns true when the stock is released (or there was nothing to release),
     * false when the reservation is already CONFIRMED and therefore cannot be given back.
     * Technical failures propagate (HTTP 503): the order is left unchanged so the caller can simply retry.
     */
    private boolean releaseStock(String orderNumber) {
        try {
            inventoryClient.release(orderNumber);
            return true;
        } catch (ReservationNotFoundException e) {
            return true;
        } catch (ReservationStateException e) {
            Optional<ReservationInfo> reservation = inventoryClient.get(orderNumber);
            return reservation.isEmpty() || !reservation.get().isConfirmed();
        }
    }

    // ------------------------------------------------------------------ recovery (called by OrderRecoveryJob)

    @Override
    public void retryPendingOrder(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        if (reserveStock(order) != ReserveOutcome.FAILED) {
            return;
        }
        boolean tooOld = order.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(pendingMaxAgeMinutes));
        if (tooOld) {
            try {
                // a reservation may exist although we never saw the answer, so release before giving up
                if (releaseStock(orderNumber)) {
                    moveTo(order, OrderStatus.PENDING, OrderStatus.CANCELLED, "Stock reservation could not be completed in time");
                }
            } catch (RuntimeException e) {
                log.warn("Could not give up on order {} yet: {}", orderNumber, e.toString());
            }
        }
    }

    @Override
    public void reconcileReservedOrder(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.STOCK_RESERVED) {
            return;
        }
        Optional<ReservationInfo> reservation = inventoryClient.get(orderNumber);
        if (reservation.isEmpty()) {
            moveTo(order, OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED, "Stock reservation not found in inventory-service");
        } else if (reservation.get().isConfirmed()) {
            moveTo(order, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED, "Aligned with inventory-service: reservation was confirmed");
        } else if (reservation.get().isPending()) {
            log.warn("Order {} is STOCK_RESERVED and its reservation is still PENDING (expires at {})", orderNumber, reservation.get().expiresAt());
        } else {
            moveTo(order, OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED,
                    "Stock reservation " + reservation.get().status().toLowerCase(Locale.ROOT) + " before the order was confirmed");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The only way an order changes status: compare-and-set in a short transaction, plus a history row and (after commit) an event.
     * Returns false when another request already moved the order (nothing was written).
     */
    private boolean moveTo(Order order, OrderStatus from, OrderStatus to, String reason) {
        String safeReason = truncate(reason);
        Boolean moved = tx.execute(status -> {
            int updated = orderRepository.transition(order.getId(), from, to, safeReason, LocalDateTime.now());
            if (updated == 0) {
                return false;
            }
            historyRepository.save(OrderStatusHistory.of(order.getId(), from, to, safeReason));
            publishStatusEvent(order, to, safeReason);
            return true;
        });
        if (Boolean.TRUE.equals(moved)) {
            log.info("Order {} {} -> {}", order.getOrderNumber(), from, to);
            return true;
        }
        log.info("Order {} was not moved {} -> {}: its status changed concurrently", order.getOrderNumber(), from, to);
        return false;
    }

    private void publishStatusEvent(Order order, OrderStatus to, String reason) {
        String topic;
        String type;
        switch (to) {
            case CONFIRMED -> { topic = Constants.TOPIC_ORDER_CONFIRMED; type = "ORDER_CONFIRMED"; }
            case CANCELLED -> { topic = Constants.TOPIC_ORDER_CANCELLED; type = "ORDER_CANCELLED"; }
            case REJECTED -> { topic = Constants.TOPIC_ORDER_REJECTED; type = "ORDER_REJECTED"; }
            default -> { return; } // STOCK_RESERVED is internal, no topic
        }
        events.publishAfterCommit(topic, order.getOrderNumber(), eventFor(type, order, to, reason));
    }

    private OrderEvent eventFor(String type, Order order, OrderStatus status, String reason) {
        return OrderEvent.of(type, order.getOrderNumber(), order.getCustomerId(), status.name(),
                order.getTotalAmount(), order.getCurrency(), reason);
    }

    private Order findOrThrow(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderNumber + " not found"));
    }

    private Order reload(String orderNumber) {
        return findOrThrow(orderNumber);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= Constants.MAX_REASON_LENGTH ? text : text.substring(0, Constants.MAX_REASON_LENGTH);
    }
}
