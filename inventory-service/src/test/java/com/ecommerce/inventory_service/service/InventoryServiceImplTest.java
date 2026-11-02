package com.ecommerce.inventory_service.service;

import com.ecommerce.inventory_service.client.ProductClient;
import com.ecommerce.inventory_service.dto.request.AdjustStockRequest;
import com.ecommerce.inventory_service.dto.request.CreateInventoryItemRequest;
import com.ecommerce.inventory_service.dto.response.AvailabilityResponse;
import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
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
import com.ecommerce.inventory_service.service.impl.InventoryServiceImpl;
import com.ecommerce.inventory_service.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock private InventoryItemRepository itemRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private ProductClient productClient;
    @Mock private InventoryEventPublisher events;
    @Mock private TransactionTemplate transactionTemplate;

    private InventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl(itemRepository, movementRepository, new InventoryMapper(),
                productClient, events, transactionTemplate);
    }

    /** TransactionTemplate is mocked, so make it simply run the callback. */
    private void transactionRunsCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    private InventoryItem item(long productId, int available, int reorderLevel) {
        return InventoryItem.builder().productId(productId).availableQuantity(available)
                .reservedQuantity(0).reorderLevel(reorderLevel).build();
    }

    @Test
    void createItem_savesItemLedgerRowAndEvent_whenProductExists() {
        transactionRunsCallback();
        when(productClient.productExists(10L)).thenReturn(true);
        when(itemRepository.existsByProductId(10L)).thenReturn(false);
        when(itemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryItemResponse response = service.createItem(new CreateInventoryItemRequest(10L, 25, 5));

        assertThat(response.productId()).isEqualTo(10L);
        assertThat(response.availableQuantity()).isEqualTo(25);
        assertThat(response.reorderLevel()).isEqualTo(5);
        assertThat(response.lowStock()).isFalse();
        verify(movementRepository).save(any(StockMovement.class));
        verify(events).publishAfterCommit(eq(Constants.TOPIC_STOCK_UPDATED), eq("10"), any(InventoryEvent.class));
        verify(events, never()).publishAfterCommit(eq(Constants.TOPIC_LOW_STOCK), anyString(), any(InventoryEvent.class));
    }

    @Test
    void createItem_publishesLowStockEvent_whenInitialStockIsAtOrBelowReorderLevel() {
        transactionRunsCallback();
        when(productClient.productExists(10L)).thenReturn(true);
        when(itemRepository.existsByProductId(10L)).thenReturn(false);
        when(itemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryItemResponse response = service.createItem(new CreateInventoryItemRequest(10L, 2, 5));

        assertThat(response.lowStock()).isTrue();
        verify(events).publishAfterCommit(eq(Constants.TOPIC_LOW_STOCK), eq("10"), any(InventoryEvent.class));
    }

    @Test
    void createItem_throwsNotFound_andTouchesNoDatabase_whenProductDoesNotExist() {
        when(productClient.productExists(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.createItem(new CreateInventoryItemRequest(99L, 5, 0)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(itemRepository, transactionTemplate);
    }

    @Test
    void createItem_throwsDuplicate_whenInventoryAlreadyExists() {
        transactionRunsCallback();
        when(productClient.productExists(10L)).thenReturn(true);
        when(itemRepository.existsByProductId(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.createItem(new CreateInventoryItemRequest(10L, 5, 0)))
                .isInstanceOf(DuplicateResourceException.class);

        verify(itemRepository, never()).save(any(InventoryItem.class));
    }

    @Test
    void adjustStock_rejectsZeroDelta() {
        assertThatThrownBy(() -> service.adjustStock(10L, new AdjustStockRequest(0, "nothing")))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(itemRepository);
    }

    @Test
    void adjustStock_throwsInsufficientStock_whenAtomicUpdateAffectsNoRows() {
        when(itemRepository.findByProductId(10L)).thenReturn(Optional.of(item(10L, 3, 0)));
        when(itemRepository.adjust(eq(10L), eq(-5), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service.adjustStock(10L, new AdjustStockRequest(-5, "damaged")))
                .isInstanceOf(InsufficientStockException.class);

        verify(movementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void adjustStock_recordsAdjustmentInLedger_whenUpdateApplies() {
        when(itemRepository.findByProductId(10L)).thenReturn(Optional.of(item(10L, 3, 0)));
        when(itemRepository.adjust(eq(10L), eq(5), any(LocalDateTime.class))).thenReturn(1);

        service.adjustStock(10L, new AdjustStockRequest(5, "goods received"));

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).save(captor.capture());
        assertThat(captor.getValue().getMovementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(captor.getValue().getQuantityDelta()).isEqualTo(5);
        assertThat(captor.getValue().getReason()).isEqualTo("goods received");
    }

    @Test
    void checkAvailability_reportsWhetherRequestedQuantityIsCovered() {
        when(itemRepository.findByProductId(10L)).thenReturn(Optional.of(item(10L, 3, 0)));

        AvailabilityResponse tooMany = service.checkAvailability(10L, 4);
        AvailabilityResponse enough = service.checkAvailability(10L, 3);

        assertThat(tooMany.sufficient()).isFalse();
        assertThat(enough.sufficient()).isTrue();
        assertThat(enough.availableQuantity()).isEqualTo(3);
    }
}