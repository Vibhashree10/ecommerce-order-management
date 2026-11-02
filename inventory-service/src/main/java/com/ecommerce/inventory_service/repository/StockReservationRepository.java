package com.ecommerce.inventory_service.repository;

import com.ecommerce.inventory_service.entity.ReservationStatus;
import com.ecommerce.inventory_service.entity.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderId(String orderId);

    /** Sorted by product id so every transaction touches inventory rows in the same order (no deadlocks). */
    List<StockReservation> findByOrderIdOrderByProductIdAsc(String orderId);

    @Query("SELECT DISTINCT r.orderId FROM StockReservation r WHERE r.status = :status AND r.expiresAt < :now")
    List<String> findOrderIdsWithExpired(@Param("status") ReservationStatus status,
                                         @Param("now") LocalDateTime now,
                                         Pageable pageable);

    /**
     * Compare-and-set on the status. Only the transaction that moves PENDING -> target gets rows > 0,
     * so concurrent confirm / release / expiry can never apply their stock effect twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockReservation r SET r.status = :to, r.updatedAt = :now "
            + "WHERE r.orderId = :orderId AND r.status = :from")
    int transition(@Param("orderId") String orderId,
                   @Param("from") ReservationStatus from,
                   @Param("to") ReservationStatus to,
                   @Param("now") LocalDateTime now);
}