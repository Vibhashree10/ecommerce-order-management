package com.ecommerce.inventory_service.service.impl;

import com.ecommerce.inventory_service.dto.request.ReservationItemRequest;
import com.ecommerce.inventory_service.dto.request.ReserveStockRequest;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;
import com.ecommerce.inventory_service.entity.MovementType;
import com.ecommerce.inventory_service.entity.ReservationStatus;
import com.ecommerce.inventory_service.entity.StockMovement;
import com.ecommerce.inventory_service.entity.StockReservation;
import com.ecommerce.inventory_service.event.InventoryEvent;
import com.ecommerce.inventory_service.event.InventoryEventPublisher;
import com.ecommerce.inventory_service.exception.InsufficientStockException;
import com.ecommerce.inventory_service.exception.InvalidReservationStateException;
import com.ecommerce.inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.inventory_service.mapper.InventoryMapper;
import com.ecommerce.inventory_service.repository.InventoryItemRepository;
import com.ecommerce.inventory_service.repository.StockMovementRepository;
import com.ecommerce.inventory_service.repository.StockReservationRepository;
import com.ecommerce.inventory_service.service.ReservationService;
import com.ecommerce.inventory_service.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stock reservation logic (participant of the order Saga).
 *
 * Concurrency design:
 *  - stock changes are atomic conditional UPDATEs (see InventoryItemRepository),
 *  - state changes are compare-and-set on the reservation status, so confirm / release / expiry
 *    racing each other can never apply their stock effect twice,
 *  - items are always processed in ascending product id order, so concurrent orders cannot deadlock,
 *  - all-or-nothing: any failure throws, which rolls back every change of the request.
 */
@Slf4j
@Service
public class ReservationServiceImpl implements ReservationService {

    private final InventoryItemRepository itemRepository;
    private final StockReservationRepository reservationRepository;
    private final StockMovementRepository movementRepository;
    private final InventoryMapper mapper;
    private final InventoryEventPublisher events;
    private final long ttlMinutes;

    public ReservationServiceImpl(InventoryItemRepository itemRepository,
                                  StockReservationRepository reservationRepository,
                                  StockMovementRepository movementRepository,
                                  InventoryMapper mapper,
                                  InventoryEventPublisher events,
                                  @Value("${inventory.reservation.ttl-minutes:15}") long ttlMinutes) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.movementRepository = movementRepository;
        this.mapper = mapper;
        this.events = events;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    @Transactional
    public ReservationResponse reserve(ReserveStockRequest request) {
        String orderId = request.orderId().trim();

        // Idempotency: the same orderId always returns the same reservation, never reserves twice.
        List<StockReservation> existing = reservationRepository.findByOrderId(orderId);
        if (!existing.isEmpty()) {
            log.info("Reservation for order {} already exists; returning it (idempotent replay)", orderId);
            return mapper.toReservationResponse(orderId, existing);
        }

        // Merge duplicate product lines. TreeMap iterates in ascending product id (lock ordering).
        Map<Long, Integer> wanted = new TreeMap<>();
        for (ReservationItemRequest item : request.items()) {
            wanted.merge(item.productId(), item.quantity(), Integer::sum);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ttlMinutes);
        List<StockReservation> created = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : wanted.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();

            int rows = itemRepository.reserve(productId, quantity, now);
            if (rows == 0) {
                // Throwing rolls back the stock already taken for earlier items of this order.
                throw insufficientOrMissing(productId, quantity);
            }
            created.add(reservationRepository.save(StockReservation.builder()
                    .orderId(orderId)
                    .productId(productId)
                    .quantity(quantity)
                    .status(ReservationStatus.PENDING)
                    .expiresAt(expiresAt)
                    .build()));
            movementRepository.save(StockMovement.of(productId, MovementType.RESERVE, -quantity,
                    "reserved for order", orderId));

            events.publishAfterCommit(Constants.TOPIC_STOCK_RESERVED, orderId,
                    InventoryEvent.of("STOCK_RESERVED", orderId, productId, quantity, null));
            publishLowStockIfNeeded(productId);
        }
        return mapper.toReservationResponse(orderId, created);
    }

    @Override
    @Transactional
    public ReservationResponse confirm(String orderId) {
        return finish(orderId.trim(), ReservationStatus.CONFIRMED);
    }

    @Override
    @Transactional
    public ReservationResponse release(String orderId) {
        return finish(orderId.trim(), ReservationStatus.RELEASED);
    }

    @Override
    @Transactional
    public void expire(String orderId) {
        finish(orderId, ReservationStatus.EXPIRED);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse get(String orderId) {
        List<StockReservation> rows = reservationRepository.findByOrderIdOrderByProductIdAsc(orderId.trim());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("No reservation found for order " + orderId);
        }
        return mapper.toReservationResponse(orderId.trim(), rows);
    }

    /**
     * Shared flow for confirm / release / expire (Template-method style, varying only the target state).
     * Step 1 is a compare-and-set: only the caller that moves PENDING -> target proceeds to touch stock.
     */
    private ReservationResponse finish(String orderId, ReservationStatus target) {
        LocalDateTime now = LocalDateTime.now();
        int moved = reservationRepository.transition(orderId, ReservationStatus.PENDING, target, now);
        List<StockReservation> rows = reservationRepository.findByOrderIdOrderByProductIdAsc(orderId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("No reservation found for order " + orderId);
        }

        if (moved == 0) {
            // Somebody already moved it (or it was never PENDING): decide whether that is fine.
            ReservationStatus current = rows.get(0).getStatus();
            boolean releaseLike = target == ReservationStatus.RELEASED || target == ReservationStatus.EXPIRED;
            boolean currentReleased = current == ReservationStatus.RELEASED || current == ReservationStatus.EXPIRED;
            if (current == target || (releaseLike && currentReleased)) {
                return mapper.toReservationResponse(orderId, rows);          // idempotent replay
            }
            if (target == ReservationStatus.EXPIRED) {
                return mapper.toReservationResponse(orderId, rows);          // lost the race to confirm: leave it
            }
            throw new InvalidReservationStateException("Reservation for order " + orderId + " is " + current
                    + " and cannot become " + target);
        }

        for (StockReservation r : rows) {
            Long productId = r.getProductId();
            int quantity = r.getQuantity();
            boolean applied;
            if (target == ReservationStatus.CONFIRMED) {
                applied = itemRepository.confirm(productId, quantity, now) == 1;
                movementRepository.save(StockMovement.of(productId, MovementType.CONFIRM, 0,
                        "stock committed for order", orderId));
                events.publishAfterCommit(Constants.TOPIC_STOCK_CONFIRMED, orderId,
                        InventoryEvent.of("STOCK_CONFIRMED", orderId, productId, quantity, null));
            } else {
                applied = itemRepository.release(productId, quantity, now) == 1;
                boolean expired = target == ReservationStatus.EXPIRED;
                movementRepository.save(StockMovement.of(productId,
                        expired ? MovementType.EXPIRE : MovementType.RELEASE, quantity,
                        expired ? "reservation expired" : "reservation released", orderId));
                events.publishAfterCommit(Constants.TOPIC_STOCK_RELEASED, orderId,
                        InventoryEvent.of(expired ? "STOCK_EXPIRED" : "STOCK_RELEASED", orderId, productId,
                                quantity, null));
            }
            if (!applied) {
                // Counters disagree with the reservation rows. Roll everything back and surface it loudly.
                throw new IllegalStateException("Inventory counters inconsistent for product " + productId
                        + " while moving order " + orderId + " to " + target);
            }
        }
        log.info("Order {} reservation moved to {}", orderId, target);
        return mapper.toReservationResponse(orderId, rows);
    }

    private RuntimeException insufficientOrMissing(Long productId, int quantity) {
        return itemRepository.findByProductId(productId)
                .<RuntimeException>map(item ->
                        new InsufficientStockException(productId, quantity, item.getAvailableQuantity()))
                .orElseGet(() -> new ResourceNotFoundException("No inventory record for product " + productId));
    }

    private void publishLowStockIfNeeded(Long productId) {
        itemRepository.findByProductId(productId)
                .filter(item -> item.getAvailableQuantity() <= item.getReorderLevel())
                .ifPresent(item -> events.publishAfterCommit(Constants.TOPIC_LOW_STOCK, String.valueOf(productId),
                        InventoryEvent.of("LOW_STOCK", null, productId, item.getReorderLevel(),
                                item.getAvailableQuantity())));
    }
}