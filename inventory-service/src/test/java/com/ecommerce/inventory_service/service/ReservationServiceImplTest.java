package com.ecommerce.inventory_service.service;

import com.ecommerce.inventory_service.dto.request.ReservationItemRequest;
import com.ecommerce.inventory_service.dto.request.ReserveStockRequest;
import com.ecommerce.inventory_service.dto.response.ReservationItemResponse;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;
import com.ecommerce.inventory_service.entity.InventoryItem;
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
import com.ecommerce.inventory_service.service.impl.ReservationServiceImpl;
import com.ecommerce.inventory_service.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock private InventoryItemRepository itemRepository;
    @Mock private StockReservationRepository reservationRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private InventoryEventPublisher events;

    private ReservationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReservationServiceImpl(itemRepository, reservationRepository, movementRepository,
                new InventoryMapper(), events, 15);
    }

    private StockReservation reservation(long productId, int qty, ReservationStatus status) {
        return StockReservation.builder().orderId("ORD-1").productId(productId).quantity(qty)
                .status(status).expiresAt(LocalDateTime.now().plusMinutes(5)).build();
    }

    // ------------------------------------------------------------------ reserve
    @Test
    void reserve_mergesDuplicateLines_processesInProductIdOrder_andRecordsLedger() {
        when(reservationRepository.findByOrderId("ORD-1")).thenReturn(List.of());
        when(itemRepository.reserve(anyLong(), anyInt(), any(LocalDateTime.class))).thenReturn(1);
        when(reservationRepository.save(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByProductId(anyLong())).thenReturn(Optional.empty());

        ReservationResponse response = service.reserve(new ReserveStockRequest("ORD-1", List.of(
                new ReservationItemRequest(2L, 3),
                new ReservationItemRequest(1L, 2),
                new ReservationItemRequest(2L, 1))));

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.items()).extracting(ReservationItemResponse::productId).containsExactly(1L, 2L);
        assertThat(response.items()).extracting(ReservationItemResponse::quantity).containsExactly(2, 4);
        verify(itemRepository).reserve(eq(1L), eq(2), any(LocalDateTime.class));
        verify(itemRepository).reserve(eq(2L), eq(4), any(LocalDateTime.class));
        verify(movementRepository, times(2)).save(any(StockMovement.class));
    }

    @Test
    void reserve_isIdempotent_whenOrderAlreadyHasReservation() {
        when(reservationRepository.findByOrderId("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.PENDING)));

        ReservationResponse response = service.reserve(new ReserveStockRequest("ORD-1",
                List.of(new ReservationItemRequest(1L, 2))));

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        verify(itemRepository, never()).reserve(anyLong(), anyInt(), any(LocalDateTime.class));
        verify(reservationRepository, never()).save(any(StockReservation.class));
    }

    @Test
    void reserve_throwsInsufficientStock_andSavesNothing_whenGuardFails() {
        when(reservationRepository.findByOrderId("ORD-1")).thenReturn(List.of());
        when(itemRepository.reserve(eq(1L), eq(5), any(LocalDateTime.class))).thenReturn(0);
        when(itemRepository.findByProductId(1L)).thenReturn(Optional.of(
                InventoryItem.builder().productId(1L).availableQuantity(2).build()));

        assertThatThrownBy(() -> service.reserve(new ReserveStockRequest("ORD-1",
                List.of(new ReservationItemRequest(1L, 5)))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("requested 5, available 2");

        verify(reservationRepository, never()).save(any(StockReservation.class));
    }

    @Test
    void reserve_failsWholeOrder_whenSecondItemHasNoStock() {
        when(reservationRepository.findByOrderId("ORD-1")).thenReturn(List.of());
        when(itemRepository.reserve(eq(1L), eq(1), any(LocalDateTime.class))).thenReturn(1);
        when(itemRepository.reserve(eq(2L), eq(1), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.save(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByProductId(1L)).thenReturn(Optional.empty());
        when(itemRepository.findByProductId(2L)).thenReturn(Optional.empty());

        // product 2 has no inventory row -> ResourceNotFoundException; Spring rolls back product 1's change
        assertThatThrownBy(() -> service.reserve(new ReserveStockRequest("ORD-1", List.of(
                new ReservationItemRequest(1L, 1), new ReservationItemRequest(2L, 1)))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ confirm
    @Test
    void confirm_commitsStockAndPublishesEvent_whenPending() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.CONFIRMED), any(LocalDateTime.class))).thenReturn(1);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.CONFIRMED)));
        when(itemRepository.confirm(eq(1L), eq(2), any(LocalDateTime.class))).thenReturn(1);

        ReservationResponse response = service.confirm("ORD-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(events).publishAfterCommit(eq(Constants.TOPIC_STOCK_CONFIRMED), eq("ORD-1"), any(InventoryEvent.class));
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).save(captor.capture());
        assertThat(captor.getValue().getMovementType()).isEqualTo(MovementType.CONFIRM);
    }

    @Test
    void confirm_isIdempotent_whenAlreadyConfirmed() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.CONFIRMED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.CONFIRMED)));

        ReservationResponse response = service.confirm("ORD-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(itemRepository, never()).confirm(anyLong(), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void confirm_throwsConflict_whenReservationWasReleased() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.CONFIRMED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.RELEASED)));

        assertThatThrownBy(() -> service.confirm("ORD-1"))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void confirm_throwsNotFound_whenOrderHasNoReservation() {
        when(reservationRepository.transition(eq("ORD-9"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.CONFIRMED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-9")).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirm("ORD-9")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirm_failsLoudly_whenCountersAreInconsistent() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.CONFIRMED), any(LocalDateTime.class))).thenReturn(1);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.CONFIRMED)));
        when(itemRepository.confirm(eq(1L), eq(2), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service.confirm("ORD-1")).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ release / expire
    @Test
    void release_returnsStockAndPublishesEvent_whenPending() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.RELEASED), any(LocalDateTime.class))).thenReturn(1);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.RELEASED)));
        when(itemRepository.release(eq(1L), eq(2), any(LocalDateTime.class))).thenReturn(1);

        ReservationResponse response = service.release("ORD-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.RELEASED);
        verify(events).publishAfterCommit(eq(Constants.TOPIC_STOCK_RELEASED), eq("ORD-1"), any(InventoryEvent.class));
        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).save(captor.capture());
        assertThat(captor.getValue().getMovementType()).isEqualTo(MovementType.RELEASE);
        assertThat(captor.getValue().getQuantityDelta()).isEqualTo(2);
    }

    @Test
    void release_isIdempotent_whenAlreadyExpired() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.RELEASED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.EXPIRED)));

        ReservationResponse response = service.release("ORD-1");

        assertThat(response.status()).isEqualTo(ReservationStatus.EXPIRED);
        verify(itemRepository, never()).release(anyLong(), anyInt(), any(LocalDateTime.class));
    }

    @Test
    void release_throwsConflict_whenAlreadyConfirmed() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.RELEASED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.release("ORD-1")).isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void expire_isANoOp_whenOrderWasConfirmedInTheMeantime() {
        when(reservationRepository.transition(eq("ORD-1"), eq(ReservationStatus.PENDING),
                eq(ReservationStatus.EXPIRED), any(LocalDateTime.class))).thenReturn(0);
        when(reservationRepository.findByOrderIdOrderByProductIdAsc("ORD-1"))
                .thenReturn(List.of(reservation(1L, 2, ReservationStatus.CONFIRMED)));

        assertThatCode(() -> service.expire("ORD-1")).doesNotThrowAnyException();

        verify(itemRepository, never()).release(anyLong(), anyInt(), any(LocalDateTime.class));
    }
}