package com.ecommerce.order_service.repository;

import com.ecommerce.order_service.entity.Order;
import com.ecommerce.order_service.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Items are fetched together with the order: open-in-view is off, so lazy loading outside a transaction would fail. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByOrderNumber(String orderNumber);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    // Separate derived queries instead of one "(:p IS NULL OR ...)" query: PostgreSQL cannot infer the type of a null parameter.
    Page<Order> findByCustomerId(String customerId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByCustomerIdAndStatus(String customerId, OrderStatus status, Pageable pageable);

    /** Candidates for the recovery jobs: order numbers only, oldest first. */
    @Query("SELECT o.orderNumber FROM PurchaseOrder o WHERE o.status = :status AND o.updatedAt < :cutoff ORDER BY o.updatedAt ASC")
    List<String> findOrderNumbersByStatusOlderThan(@Param("status") OrderStatus status,
                                                   @Param("cutoff") LocalDateTime cutoff,
                                                   Pageable pageable);

    /**
     * Compare-and-set on the status. Only the caller that moves the order from 'from' to 'to' gets 1;
     * a concurrent caller (confirm vs cancel vs recovery job) gets 0 and must not apply its side effects.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PurchaseOrder o SET o.status = :toStatus, o.failureReason = :reason, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.status = :fromStatus")
    int transition(@Param("id") Long id,
                   @Param("fromStatus") OrderStatus from,
                   @Param("toStatus") OrderStatus to,
                   @Param("reason") String reason,
                   @Param("now") LocalDateTime now);
}
