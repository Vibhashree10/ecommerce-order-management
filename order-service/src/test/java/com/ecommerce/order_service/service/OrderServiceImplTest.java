package com.ecommerce.order_service.service;

import com.ecommerce.order_service.client.InventoryClient;
import com.ecommerce.order_service.client.ProductClient;
import com.ecommerce.order_service.client.ProductInfo;
import com.ecommerce.order_service.client.ReservationInfo;
import com.ecommerce.order_service.client.ReserveItem;
import com.ecommerce.order_service.dto.request.CreateOrderRequest;
import com.ecommerce.order_service.dto.request.OrderItemRequest;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderItem;
import com.ecommerce.order_service.entity.OrderStatus;
import com.ecommerce.order_service.entity.OrderStatusHistory;
import com.ecommerce.order_service.event.OrderEventPublisher;
import com.ecommerce.order_service.exception.ExternalServiceException;
import com.ecommerce.order_service.exception.InvalidOrderException;
import com.ecommerce.order_service.exception.InvalidOrderStateException;
import com.ecommerce.order_service.exception.ReservationNotFoundException;
import com.ecommerce.order_service.exception.ReservationStateException;
import com.ecommerce.order_service.exception.ResourceNotFoundException;
import com.ecommerce.order_service.exception.StockUnavailableException;
import com.ecommerce.order_service.mapper.OrderMapper;
import com.ecommerce.order_service.repository.OrderRepository;
import com.ecommerce.order_service.repository.OrderStatusHistoryRepository;
import com.ecommerce.order_service.service.impl.OrderServiceImpl;
import com.ecommerce.order_service.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Saga orchestration rules, with the repositories, both clients and the transaction template mocked. */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusHistoryRepository historyRepository;
    @Mock private InventoryClient inventoryClient;
    @Mock private ProductClient productClient;
    @Mock private OrderEventPublisher events;
    @Mock private TransactionTemplate tx;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        // run transaction callbacks inline
        lenient().when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        // every compare-and-set succeeds unless a test says otherwise
        lenient().when(orderRepository.transition(any(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });
        lenient().when(productClient.isEnabled()).thenReturn(false);
        service = new OrderServiceImpl(orderRepository, historyRepository, inventoryClient, productClient,
                new OrderMapper(), events, tx, "USD", 3, 10);
    }

    // ------------------------------------------------------------------ helpers

    private Order order(OrderStatus status) {
        Order o = Order.create("ORD-1", "cust-1", "USD", null);
        o.setId(1L);
        o.addItem(OrderItem.of(5L, null, null, new BigDecimal("10.00"), 2));
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        return o;
    }

    private CreateOrderRequest request(Long productId, int qty, String price) {
        return new CreateOrderRequest("cust-1", null,
                List.of(new OrderItemRequest(productId, qty, price == null ? null : new BigDecimal(price))));
    }

    private void stubExistingOrder(Order o) {
        lenient().when(orderRepository.findByOrderNumber(o.getOrderNumber())).thenReturn(Optional.of(o));
    }

    private static ReservationInfo reservation(String status) {
        return new ReservationInfo("ORD-1", status, null);
    }

    private void verifyMoved(OrderStatus from, OrderStatus to) {
        verify(orderRepository).transition(eq(1L), eq(from), eq(to), any(), any());
    }

    private void verifyNotMoved() {
        verify(orderRepository, never()).transition(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_reservesStockAndMovesOrderToStockReserved() {
        lenient().when(orderRepository.findByOrderNumber(anyString())).thenAnswer(inv -> {
            Order o = order(OrderStatus.STOCK_RESERVED);
            o.setOrderNumber(inv.getArgument(0));
            return Optional.of(o);
        });

        OrderResponse response = service.createOrder(request(5L, 2, "10.00"), null);

        assertThat(response.status()).isEqualTo(OrderStatus.STOCK_RESERVED);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("20.00");
        assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
        assertThat(saved.getValue().getOrderNumber()).matches("ORD-\\d{8}-[0-9A-F]{8}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReserveItem>> items = (ArgumentCaptor<List<ReserveItem>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(inventoryClient).reserve(eq(saved.getValue().getOrderNumber()), items.capture());
        assertThat(items.getValue()).containsExactly(new ReserveItem(5L, 2));
        verifyMoved(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED);

        ArgumentCaptor<OrderStatusHistory> history = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(historyRepository, times(2)).save(history.capture());   // creation row + PENDING->STOCK_RESERVED row
        assertThat(history.getAllValues().get(0).getFromStatus()).isNull();
        assertThat(history.getAllValues().get(1).getToStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        verify(events).publishAfterCommit(eq(Constants.TOPIC_ORDER_CREATED), anyString(), any());
    }

    @Test
    void create_rejectsOrderWhenInventoryHasNotEnoughStock() {
        lenient().when(orderRepository.findByOrderNumber(anyString())).thenReturn(Optional.of(order(OrderStatus.REJECTED)));
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new StockUnavailableException("Insufficient stock for product 5"));

        OrderResponse response = service.createOrder(request(5L, 2, "10.00"), null);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        verify(orderRepository).transition(eq(1L), eq(OrderStatus.PENDING), eq(OrderStatus.REJECTED),
                eq("Insufficient stock for product 5"), any());
        verify(events).publishAfterCommit(eq(Constants.TOPIC_ORDER_REJECTED), anyString(), any());
    }

    @Test
    void create_keepsOrderPendingWhenInventoryIsUnreachable() {
        lenient().when(orderRepository.findByOrderNumber(anyString())).thenReturn(Optional.of(order(OrderStatus.PENDING)));
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new ExternalServiceException("inventory-service unreachable"));

        OrderResponse response = service.createOrder(request(5L, 2, "10.00"), null);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        verifyNotMoved();
    }

    @Test
    void create_withKnownIdempotencyKeyReturnsTheExistingOrderWithoutReserving() {
        when(orderRepository.findByCustomerIdAndIdempotencyKey("cust-1", "key-1")).thenReturn(Optional.of(order(OrderStatus.STOCK_RESERVED)));

        OrderResponse response = service.createOrder(request(5L, 2, "10.00"), " key-1 ");

        assertThat(response.orderNumber()).isEqualTo("ORD-1");
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void create_whenTwoIdenticalRequestsRaceTheLoserReturnsTheWinnersOrder() {
        when(orderRepository.findByCustomerIdAndIdempotencyKey("cust-1", "key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(order(OrderStatus.STOCK_RESERVED)));
        // doThrow: calling save() inside when(...) would run the lenient answer from setUp with a null argument
        doThrow(new DataIntegrityViolationException("uk_orders_customer_idempotency")).when(orderRepository).save(any(Order.class));

        OrderResponse response = service.createOrder(request(5L, 2, "10.00"), "key-1");

        assertThat(response.orderNumber()).isEqualTo("ORD-1");
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void create_rejectsTheSameProductTwice() {
        CreateOrderRequest dup = new CreateOrderRequest("cust-1", null, List.of(
                new OrderItemRequest(5L, 1, BigDecimal.ONE), new OrderItemRequest(5L, 2, BigDecimal.ONE)));

        assertThatThrownBy(() -> service.createOrder(dup, null))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("more than once");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_rejectsMoreLinesThanTheConfiguredMaximum() {
        List<OrderItemRequest> lines = List.of(
                new OrderItemRequest(1L, 1, BigDecimal.ONE), new OrderItemRequest(2L, 1, BigDecimal.ONE),
                new OrderItemRequest(3L, 1, BigDecimal.ONE), new OrderItemRequest(4L, 1, BigDecimal.ONE));

        assertThatThrownBy(() -> service.createOrder(new CreateOrderRequest("cust-1", null, lines), null))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("at most 3");
    }

    @Test
    void create_requiresUnitPriceWhenProductLookupIsDisabled() {
        assertThatThrownBy(() -> service.createOrder(request(5L, 1, null), null))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("unitPrice is required");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void create_usesTheCatalogPriceNotTheClientPriceWhenProductLookupIsEnabled() {
        when(productClient.isEnabled()).thenReturn(true);
        when(productClient.find(5L)).thenReturn(Optional.of(new ProductInfo(5L, "SKU-5", "Keyboard", new BigDecimal("49.99"), true)));
        lenient().when(orderRepository.findByOrderNumber(anyString())).thenReturn(Optional.of(order(OrderStatus.STOCK_RESERVED)));

        service.createOrder(request(5L, 2, "0.01"), null);   // client tries to pay one cent

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        OrderItem line = saved.getValue().getItems().get(0);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("49.99");
        assertThat(line.getSku()).isEqualTo("SKU-5");
        assertThat(line.getProductName()).isEqualTo("Keyboard");
        assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("99.98");
    }

    @Test
    void create_rejectsUnknownAndInactiveProducts() {
        when(productClient.isEnabled()).thenReturn(true);
        when(productClient.find(1L)).thenReturn(Optional.empty());
        when(productClient.find(2L)).thenReturn(Optional.of(new ProductInfo(2L, "S", "N", BigDecimal.TEN, false)));

        assertThatThrownBy(() -> service.createOrder(request(1L, 1, null), null))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> service.createOrder(request(2L, 1, null), null))
                .isInstanceOf(InvalidOrderException.class).hasMessageContaining("not available");
        verify(orderRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ read

    @Test
    void getOrder_throwsNotFoundForUnknownOrder() {
        when(orderRepository.findByOrderNumber("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder("NOPE")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ confirm

    @Test
    void confirm_commitsTheStockAndMovesOrderToConfirmed() {
        Order o = order(OrderStatus.STOCK_RESERVED);
        stubExistingOrder(o);
        when(inventoryClient.confirm("ORD-1")).thenReturn(reservation("CONFIRMED"));

        service.confirmOrder("ORD-1");

        verify(inventoryClient).confirm("ORD-1");
        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
        verify(events).publishAfterCommit(eq(Constants.TOPIC_ORDER_CONFIRMED), eq("ORD-1"), any());
    }

    @Test
    void confirm_isIdempotentForAnAlreadyConfirmedOrder() {
        stubExistingOrder(order(OrderStatus.CONFIRMED));

        OrderResponse response = service.confirmOrder("ORD-1");

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        verifyNoInteractions(inventoryClient);
        verifyNotMoved();
    }

    @Test
    void confirm_refusesAPendingOrder() {
        stubExistingOrder(order(OrderStatus.PENDING));

        assertThatThrownBy(() -> service.confirmOrder("ORD-1"))
                .isInstanceOf(InvalidOrderStateException.class).hasMessageContaining("PENDING");
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void confirm_refusesCancelledAndRejectedOrders() {
        stubExistingOrder(order(OrderStatus.CANCELLED));
        assertThatThrownBy(() -> service.confirmOrder("ORD-1")).isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void confirm_cancelsTheOrderWhenTheReservationExpired() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.confirm("ORD-1")).thenThrow(new ReservationStateException("Reservation for order ORD-1 is EXPIRED"));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("EXPIRED")));

        assertThatThrownBy(() -> service.confirmOrder("ORD-1"))
                .isInstanceOf(InvalidOrderStateException.class).hasMessageContaining("no longer valid");
        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED);
    }

    @Test
    void confirm_treatsAnAlreadyConfirmedReservationAsSuccess() {
        // an earlier confirm reached inventory but its reply was lost
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.confirm("ORD-1")).thenThrow(new ReservationStateException("already CONFIRMED"));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("CONFIRMED")));

        service.confirmOrder("ORD-1");

        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void confirm_leavesTheOrderUntouchedWhenInventoryIsDown() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.confirm("ORD-1")).thenThrow(new ExternalServiceException("inventory-service unreachable"));

        assertThatThrownBy(() -> service.confirmOrder("ORD-1")).isInstanceOf(ExternalServiceException.class);
        verifyNotMoved();
    }

    @Test
    void confirm_losingTheRaceAgainstACancelIsReportedAsConflict() {
        Order o = order(OrderStatus.STOCK_RESERVED);
        Order cancelled = order(OrderStatus.CANCELLED);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(o)).thenReturn(Optional.of(cancelled));
        when(inventoryClient.confirm("ORD-1")).thenReturn(reservation("CONFIRMED"));
        when(orderRepository.transition(eq(1L), eq(OrderStatus.STOCK_RESERVED), eq(OrderStatus.CONFIRMED), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirmOrder("ORD-1"))
                .isInstanceOf(InvalidOrderStateException.class).hasMessageContaining("CANCELLED");
        verify(historyRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancel_releasesTheStockAndMovesOrderToCancelled() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));

        service.cancelOrder("ORD-1", "changed my mind");

        verify(inventoryClient).release("ORD-1");
        verify(orderRepository).transition(eq(1L), eq(OrderStatus.STOCK_RESERVED), eq(OrderStatus.CANCELLED), eq("changed my mind"), any());
        verify(events).publishAfterCommit(eq(Constants.TOPIC_ORDER_CANCELLED), eq("ORD-1"), any());
    }

    @Test
    void cancel_usesADefaultReasonWhenNoneIsGiven() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));

        service.cancelOrder("ORD-1", "  ");

        verify(orderRepository).transition(any(), any(), eq(OrderStatus.CANCELLED), eq("Cancelled by customer"), any());
    }

    @Test
    void cancel_isIdempotentForAnAlreadyCancelledOrder() {
        stubExistingOrder(order(OrderStatus.CANCELLED));

        OrderResponse response = service.cancelOrder("ORD-1", null);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verifyNoInteractions(inventoryClient);
        verifyNotMoved();
    }

    @Test
    void cancel_refusesConfirmedAndRejectedOrders() {
        stubExistingOrder(order(OrderStatus.CONFIRMED));
        assertThatThrownBy(() -> service.cancelOrder("ORD-1", null)).isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(inventoryClient);
    }

    @Test
    void cancel_ofAPendingOrderAlsoReleasesBecauseAReservationMayExist() {
        stubExistingOrder(order(OrderStatus.PENDING));

        service.cancelOrder("ORD-1", null);

        verify(inventoryClient).release("ORD-1");
        verifyMoved(OrderStatus.PENDING, OrderStatus.CANCELLED);
    }

    @Test
    void cancel_stillCancelsWhenInventoryHasNoReservation() {
        stubExistingOrder(order(OrderStatus.PENDING));
        when(inventoryClient.release("ORD-1")).thenThrow(new ReservationNotFoundException("No reservation found for order ORD-1"));

        service.cancelOrder("ORD-1", null);

        verifyMoved(OrderStatus.PENDING, OrderStatus.CANCELLED);
    }

    @Test
    void cancel_leavesTheOrderUntouchedWhenInventoryIsDown() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.release("ORD-1")).thenThrow(new ExternalServiceException("inventory-service unreachable"));

        assertThatThrownBy(() -> service.cancelOrder("ORD-1", null)).isInstanceOf(ExternalServiceException.class);
        verifyNotMoved();
    }

    @Test
    void cancel_whenInventoryAlreadyConfirmedTheStockTheOrderBecomesConfirmedAndCancelIsRefused() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.release("ORD-1")).thenThrow(new ReservationStateException("Reservation is CONFIRMED"));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("CONFIRMED")));

        assertThatThrownBy(() -> service.cancelOrder("ORD-1", null))
                .isInstanceOf(InvalidOrderStateException.class).hasMessageContaining("already confirmed");
        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
        verify(orderRepository, never()).transition(any(), any(), eq(OrderStatus.CANCELLED), any(), any());
    }

    @Test
    void cancel_whenTheReservationAlreadyExpiredTheCancelStillSucceeds() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.release("ORD-1")).thenThrow(new ReservationStateException("Reservation is EXPIRED"));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("EXPIRED")));

        service.cancelOrder("ORD-1", null);

        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED);
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void retryPending_movesOrderToStockReservedWhenInventoryIsBackUp() {
        stubExistingOrder(order(OrderStatus.PENDING));

        service.retryPendingOrder("ORD-1");

        verify(inventoryClient).reserve(eq("ORD-1"), anyList());
        verifyMoved(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED);
    }

    @Test
    void retryPending_rejectsWhenStockIsGone() {
        stubExistingOrder(order(OrderStatus.PENDING));
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new StockUnavailableException("no stock"));

        service.retryPendingOrder("ORD-1");

        verifyMoved(OrderStatus.PENDING, OrderStatus.REJECTED);
    }

    @Test
    void retryPending_ignoresOrdersThatAreNoLongerPending() {
        stubExistingOrder(order(OrderStatus.CONFIRMED));

        service.retryPendingOrder("ORD-1");

        verifyNoInteractions(inventoryClient);
    }

    @Test
    void retryPending_youngOrderStaysPendingWhileInventoryIsStillDown() {
        stubExistingOrder(order(OrderStatus.PENDING));
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new ExternalServiceException("down"));

        service.retryPendingOrder("ORD-1");

        verifyNotMoved();
        verify(inventoryClient, never()).release(anyString());
    }

    @Test
    void retryPending_oldOrderIsReleasedAndCancelledWhenInventoryStillFails() {
        Order old = order(OrderStatus.PENDING);
        old.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        stubExistingOrder(old);
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new ExternalServiceException("down"));

        service.retryPendingOrder("ORD-1");

        verify(inventoryClient).release("ORD-1");
        verify(orderRepository).transition(eq(1L), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                eq("Stock reservation could not be completed in time"), any());
    }

    @Test
    void retryPending_oldOrderStaysPendingIfTheReleaseAlsoFails() {
        Order old = order(OrderStatus.PENDING);
        old.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        stubExistingOrder(old);
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new ExternalServiceException("down"));
        when(inventoryClient.release("ORD-1")).thenThrow(new ExternalServiceException("down"));

        service.retryPendingOrder("ORD-1");

        verifyNotMoved();
    }

    @Test
    void reconcile_cancelsAnOrderWhoseReservationExpired() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("EXPIRED")));

        service.reconcileReservedOrder("ORD-1");

        verify(orderRepository).transition(eq(1L), eq(OrderStatus.STOCK_RESERVED), eq(OrderStatus.CANCELLED),
                eq("Stock reservation expired before the order was confirmed"), any());
    }

    @Test
    void reconcile_cancelsAnOrderWhoseReservationDisappeared() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.empty());

        service.reconcileReservedOrder("ORD-1");

        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED);
    }

    @Test
    void reconcile_confirmsAnOrderWhoseReservationWasConfirmed() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("CONFIRMED")));

        service.reconcileReservedOrder("ORD-1");

        verifyMoved(OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void reconcile_leavesAnOrderAloneWhileItsReservationIsStillPending() {
        stubExistingOrder(order(OrderStatus.STOCK_RESERVED));
        when(inventoryClient.get("ORD-1")).thenReturn(Optional.of(reservation("PENDING")));

        service.reconcileReservedOrder("ORD-1");

        verifyNotMoved();
    }
}
