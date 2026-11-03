package com.ecommerce.order_service.repository;

import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderItem;
import com.ecommerce.order_service.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway migrations, the entity mapping and the compare-and-set UPDATE against a real PostgreSQL.
 * Skipped automatically when Docker is not running (disabledWithoutDocker), so it never breaks a build.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class OrderRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private OrderRepository repository;
    @Autowired private EntityManager em;

    private Order newOrder(String number, String customer, String key) {
        Order o = Order.create(number, customer, "USD", key);
        o.addItem(OrderItem.of(2L, "SKU-2", "Two", new BigDecimal("5.00"), 3));
        o.addItem(OrderItem.of(1L, "SKU-1", "One", new BigDecimal("1.50"), 2));
        return o;
    }

    @Test
    void savesOrderWithItemsAndComputesTotals() {
        repository.saveAndFlush(newOrder("ORD-T1", "c1", null));
        em.clear();

        Order loaded = repository.findByOrderNumber("ORD-T1").orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("18.00");   // 5.00*3 + 1.50*2
        assertThat(loaded.getItems()).extracting(OrderItem::getProductId).containsExactly(1L, 2L);   // @OrderBy productId
        assertThat(loaded.getItems().get(0).getLineTotal()).isEqualByComparingTo("3.00");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sameCustomerCannotReuseAnIdempotencyKeyButOtherCustomersCan() {
        repository.saveAndFlush(newOrder("ORD-T2", "c2", "key-1"));
        repository.saveAndFlush(newOrder("ORD-T3", "c3", "key-1"));   // different customer: fine

        assertThatThrownBy(() -> repository.saveAndFlush(newOrder("ORD-T4", "c2", "key-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordersWithoutIdempotencyKeyNeverClash() {
        repository.saveAndFlush(newOrder("ORD-T5", "c4", null));
        repository.saveAndFlush(newOrder("ORD-T6", "c4", null));   // NULL keys are distinct in a unique index

        assertThat(repository.findByCustomerId("c4", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void findsOrderByCustomerAndIdempotencyKey() {
        repository.saveAndFlush(newOrder("ORD-T7", "c5", "abc"));
        em.clear();

        assertThat(repository.findByCustomerIdAndIdempotencyKey("c5", "abc")).isPresent();
        assertThat(repository.findByCustomerIdAndIdempotencyKey("c5", "zzz")).isEmpty();
    }

    @Test
    void transition_isACompareAndSetThatOnlyOneCallerWins() {
        Order saved = repository.saveAndFlush(newOrder("ORD-T8", "c6", null));
        LocalDateTime now = LocalDateTime.now();

        int first = repository.transition(saved.getId(), OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, null, now);
        int second = repository.transition(saved.getId(), OrderStatus.PENDING, OrderStatus.CANCELLED, "late", now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);   // status is no longer PENDING
        Order loaded = repository.findByOrderNumber("ORD-T8").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        assertThat(loaded.getFailureReason()).isNull();
    }

    @Test
    void transition_storesTheReason() {
        Order saved = repository.saveAndFlush(newOrder("ORD-T9", "c7", null));

        repository.transition(saved.getId(), OrderStatus.PENDING, OrderStatus.REJECTED, "Insufficient stock", LocalDateTime.now());

        assertThat(repository.findByOrderNumber("ORD-T9").orElseThrow().getFailureReason()).isEqualTo("Insufficient stock");
    }

    @Test
    void filtersByCustomerAndStatus() {
        Order a = repository.saveAndFlush(newOrder("ORD-T10", "c8", null));
        repository.saveAndFlush(newOrder("ORD-T11", "c8", null));
        repository.transition(a.getId(), OrderStatus.PENDING, OrderStatus.CONFIRMED, null, LocalDateTime.now());
        em.clear();

        assertThat(repository.findByCustomerIdAndStatus("c8", OrderStatus.CONFIRMED, PageRequest.of(0, 10)).getContent())
                .extracting(Order::getOrderNumber).containsExactly("ORD-T10");
        assertThat(repository.findByStatus(OrderStatus.CONFIRMED, PageRequest.of(0, 100)).getContent())
                .extracting(Order::getOrderNumber).contains("ORD-T10").doesNotContain("ORD-T11");
    }

    @Test
    void findOrderNumbersByStatusOlderThan_returnsOnlyStaleOrdersOfThatStatus() {
        repository.saveAndFlush(newOrder("ORD-T12", "c9", null));
        em.clear();

        List<String> future = repository.findOrderNumbersByStatusOlderThan(OrderStatus.PENDING, LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 500));
        List<String> past = repository.findOrderNumbersByStatusOlderThan(OrderStatus.PENDING, LocalDateTime.now().minusMinutes(1), PageRequest.of(0, 500));
        List<String> otherStatus = repository.findOrderNumbersByStatusOlderThan(OrderStatus.CONFIRMED, LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 500));

        assertThat(future).contains("ORD-T12");
        assertThat(past).doesNotContain("ORD-T12");
        assertThat(otherStatus).doesNotContain("ORD-T12");
    }

    @Test
    void migrationRejectsInvalidStatusValues() {
        repository.saveAndFlush(newOrder("ORD-T13", "c10", null));

        assertThatThrownBy(() -> {
            em.createNativeQuery("UPDATE orders SET status = 'SHIPPED' WHERE order_number = 'ORD-T13'").executeUpdate();
            em.flush();
        }).hasMessageContaining("check constraint");
    }
}
