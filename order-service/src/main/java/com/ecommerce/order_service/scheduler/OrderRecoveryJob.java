package com.ecommerce.order_service.scheduler;

import com.ecommerce.order_service.entity.OrderStatus;
import com.ecommerce.order_service.repository.OrderRepository;
import com.ecommerce.order_service.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saga recovery. Two kinds of orders can get stuck because a remote call failed or a reply was lost:
 *  - PENDING        : stock reservation not answered -> retry it (idempotent), give up after orders.pending-max-age-minutes
 *  - STOCK_RESERVED : nobody confirmed in time -> inventory-service expired the reservation (TTL 15 min), so the order is cancelled
 *                     (orders.reservation-sync-after-minutes must stay LARGER than inventory's TTL)
 * Each order is handled in its own try/catch, so one bad order cannot block the others.
 * Safe with several instances running: every status change is a compare-and-set, and remote calls are idempotent.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "orders.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class OrderRecoveryJob {

    private static final int BATCH_SIZE = 50;

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final long pendingRetryAfterSeconds;
    private final long reservationSyncAfterMinutes;

    public OrderRecoveryJob(OrderRepository orderRepository,
                            OrderService orderService,
                            @Value("${orders.pending-retry-after-seconds:60}") long pendingRetryAfterSeconds,
                            @Value("${orders.reservation-sync-after-minutes:16}") long reservationSyncAfterMinutes) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.pendingRetryAfterSeconds = pendingRetryAfterSeconds;
        this.reservationSyncAfterMinutes = reservationSyncAfterMinutes;
    }

    @Scheduled(fixedDelayString = "${orders.jobs-scan-ms:60000}", initialDelayString = "${orders.jobs-scan-ms:60000}")
    public void run() {
        retryPending();
        reconcileReserved();
    }

    void retryPending() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(pendingRetryAfterSeconds);
        List<String> numbers = orderRepository.findOrderNumbersByStatusOlderThan(OrderStatus.PENDING, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (String number : numbers) {
            try {
                orderService.retryPendingOrder(number);
            } catch (RuntimeException e) {
                log.warn("Recovery of PENDING order {} failed: {}", number, e.toString());
            }
        }
    }

    void reconcileReserved() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(reservationSyncAfterMinutes);
        List<String> numbers = orderRepository.findOrderNumbersByStatusOlderThan(OrderStatus.STOCK_RESERVED, cutoff, PageRequest.of(0, BATCH_SIZE));
        for (String number : numbers) {
            try {
                orderService.reconcileReservedOrder(number);
            } catch (RuntimeException e) {
                log.warn("Reconciling STOCK_RESERVED order {} failed: {}", number, e.toString());
            }
        }
    }
}
