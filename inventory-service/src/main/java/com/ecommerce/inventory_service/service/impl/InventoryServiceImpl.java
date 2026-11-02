package com.ecommerce.inventory_service.service.impl;

import com.ecommerce.inventory_service.client.ProductClient;
import com.ecommerce.inventory_service.dto.request.AdjustStockRequest;
import com.ecommerce.inventory_service.dto.request.CreateInventoryItemRequest;
import com.ecommerce.inventory_service.dto.response.AvailabilityResponse;
import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
import com.ecommerce.inventory_service.dto.response.PageResponse;
import com.ecommerce.inventory_service.dto.response.StockMovementResponse;
import com.ecommerce.inventory_service.entity.InventoryItem;
import com.ecommerce.inventory_service.entity.MovementType;
import com.ecommerce.inventory_service.entity.StockMovement;
import com.ecommerce.inventory_service.event.InventoryEvent;
import com.ecommerce.inventory_service.event.InventoryEventPublisher;
import com.ecommerce.inventory_service.exception.DuplicateResourceException;
import com.ecommerce.inventory_service.exception.InsufficientStockException;
import com.ecommerce.inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.inventory_service.mapper.InventoryMapper;
import com.ecommerce.inventory_service.repository.InventoryItemRepository;
import com.ecommerce.inventory_service.repository.StockMovementRepository;
import com.ecommerce.inventory_service.service.InventoryService;
import com.ecommerce.inventory_service.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    // NOTE: field order = constructor parameter order (used by the unit test).
    private final InventoryItemRepository itemRepository;
    private final StockMovementRepository movementRepository;
    private final InventoryMapper mapper;
    private final ProductClient productClient;
    private final InventoryEventPublisher events;
    private final TransactionTemplate transactionTemplate;

    /**
     * >>> INTER-SERVICE CALL (Inventory -> Product).
     * The remote call is made BEFORE the database transaction starts. Holding a DB connection
     * open while waiting for another service is an anti-pattern: a slow product-service would
     * exhaust the small connection pool. Hence no @Transactional on this method;
     * the database work runs inside TransactionTemplate afterwards.
     */
    @Override
    public InventoryItemResponse createItem(CreateInventoryItemRequest request) {
        if (!productClient.productExists(request.productId())) {
            throw new ResourceNotFoundException("Product " + request.productId() + " does not exist in product-service");
        }
        return transactionTemplate.execute(status -> persistNewItem(request));
    }

    private InventoryItemResponse persistNewItem(CreateInventoryItemRequest request) {
        Long productId = request.productId();
        if (itemRepository.existsByProductId(productId)) {
            throw new DuplicateResourceException("Inventory already exists for product " + productId);
        }
        int quantity = request.quantity();
        int reorderLevel = request.reorderLevel() == null ? 0 : request.reorderLevel();

        InventoryItem item = itemRepository.save(InventoryItem.builder()
                .productId(productId)
                .availableQuantity(quantity)
                .reservedQuantity(0)
                .reorderLevel(reorderLevel)
                .build());
        movementRepository.save(StockMovement.of(productId, MovementType.INITIAL, quantity, "initial stock", null));

        events.publishAfterCommit(Constants.TOPIC_STOCK_UPDATED, String.valueOf(productId),
                InventoryEvent.of("STOCK_CREATED", null, productId, quantity, quantity));
        publishLowStockIfNeeded(item);
        return mapper.toResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemResponse getItem(Long productId) {
        return mapper.toResponse(findItem(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> listItems(Pageable pageable) {
        return PageResponse.from(itemRepository.findAll(pageable), mapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> listLowStock(Pageable pageable) {
        return PageResponse.from(itemRepository.findLowStock(pageable), mapper::toResponse);
    }

    @Override
    @Transactional
    public InventoryItemResponse adjustStock(Long productId, AdjustStockRequest request) {
        int delta = request.delta();
        if (delta == 0) {
            throw new IllegalArgumentException("delta must not be zero");
        }
        InventoryItem before = findItem(productId);

        // Atomic guard: the UPDATE only applies if available + delta stays >= 0.
        int rows = itemRepository.adjust(productId, delta, LocalDateTime.now());
        if (rows == 0) {
            throw new InsufficientStockException(productId, -delta, before.getAvailableQuantity());
        }
        movementRepository.save(StockMovement.of(productId, MovementType.ADJUSTMENT, delta, request.reason(), null));

        InventoryItem after = findItem(productId);
        events.publishAfterCommit(Constants.TOPIC_STOCK_UPDATED, String.valueOf(productId),
                InventoryEvent.of("STOCK_ADJUSTED", null, productId, delta, after.getAvailableQuantity()));
        publishLowStockIfNeeded(after);
        return mapper.toResponse(after);
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(Long productId, int quantity) {
        InventoryItem item = findItem(productId);
        return new AvailabilityResponse(productId, quantity, item.getAvailableQuantity(),
                item.getAvailableQuantity() >= quantity);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockMovementResponse> getMovements(Long productId, Pageable pageable) {
        findItem(productId); // 404 if the product has no inventory record
        return PageResponse.from(
                movementRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable), mapper::toMovementResponse);
    }

    private InventoryItem findItem(Long productId) {
        return itemRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory record for product " + productId));
    }

    private void publishLowStockIfNeeded(InventoryItem item) {
        if (item.getAvailableQuantity() <= item.getReorderLevel()) {
            events.publishAfterCommit(Constants.TOPIC_LOW_STOCK, String.valueOf(item.getProductId()),
                    InventoryEvent.of("LOW_STOCK", null, item.getProductId(), item.getReorderLevel(),
                            item.getAvailableQuantity()));
        }
    }
}