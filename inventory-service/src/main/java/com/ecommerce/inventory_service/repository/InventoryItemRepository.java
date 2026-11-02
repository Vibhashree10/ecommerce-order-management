package com.ecommerce.inventory_service.repository;

import com.ecommerce.inventory_service.entity.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Every stock change is ONE atomic conditional UPDATE. The WHERE clause is the guard
 * (e.g. availableQuantity >= :qty). It returns the number of rows changed:
 * 1 = applied, 0 = guard failed (not enough stock / product missing).
 * This is the relational equivalent of a DynamoDB conditional write, and it is what
 * makes overselling impossible under concurrency without holding long locks.
 *
 * clearAutomatically: bulk updates bypass the persistence context, so we clear it
 * to avoid reading stale entities afterwards.
 */
@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    @Query(value = "SELECT i FROM InventoryItem i WHERE i.availableQuantity <= i.reorderLevel",
            countQuery = "SELECT COUNT(i) FROM InventoryItem i WHERE i.availableQuantity <= i.reorderLevel")
    Page<InventoryItem> findLowStock(Pageable pageable);

    /** available -= qty, reserved += qty  (only if enough available). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InventoryItem i SET i.availableQuantity = i.availableQuantity - :qty, "
            + "i.reservedQuantity = i.reservedQuantity + :qty, i.updatedAt = :now "
            + "WHERE i.productId = :productId AND i.availableQuantity >= :qty")
    int reserve(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") LocalDateTime now);

    /** available += qty, reserved -= qty  (only if that much is reserved). Used by release and expiry. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InventoryItem i SET i.availableQuantity = i.availableQuantity + :qty, "
            + "i.reservedQuantity = i.reservedQuantity - :qty, i.updatedAt = :now "
            + "WHERE i.productId = :productId AND i.reservedQuantity >= :qty")
    int release(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") LocalDateTime now);

    /** reserved -= qty: the stock leaves the warehouse (order confirmed). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InventoryItem i SET i.reservedQuantity = i.reservedQuantity - :qty, i.updatedAt = :now "
            + "WHERE i.productId = :productId AND i.reservedQuantity >= :qty")
    int confirm(@Param("productId") Long productId, @Param("qty") int qty, @Param("now") LocalDateTime now);

    /** available += delta (delta may be negative, but available may never go below 0). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InventoryItem i SET i.availableQuantity = i.availableQuantity + :delta, i.updatedAt = :now "
            + "WHERE i.productId = :productId AND i.availableQuantity + :delta >= 0")
    int adjust(@Param("productId") Long productId, @Param("delta") int delta, @Param("now") LocalDateTime now);
}