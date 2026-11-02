package com.ecommerce.inventory_service.repository;

import com.ecommerce.inventory_service.entity.InventoryItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real Flyway migrations and the atomic UPDATE queries against a real PostgreSQL.
 * Skipped automatically when Docker is not running (disabledWithoutDocker), so it never breaks a build.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class InventoryItemRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private InventoryItemRepository repository;

    private InventoryItem saveItem(long productId, int available, int reserved) {
        return repository.save(InventoryItem.builder().productId(productId)
                .availableQuantity(available).reservedQuantity(reserved).reorderLevel(1).build());
    }

    @Test
    void reserve_appliesOnlyWhileEnoughStockIsAvailable() {
        saveItem(1L, 5, 0);
        LocalDateTime now = LocalDateTime.now();

        assertThat(repository.reserve(1L, 3, now)).isEqualTo(1);
        assertThat(repository.reserve(1L, 3, now)).isEqualTo(0);   // only 2 left: guard refuses

        InventoryItem item = repository.findByProductId(1L).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(2);
        assertThat(item.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void release_movesStockBackFromReservedToAvailable() {
        saveItem(2L, 0, 4);

        assertThat(repository.release(2L, 4, LocalDateTime.now())).isEqualTo(1);
        assertThat(repository.release(2L, 1, LocalDateTime.now())).isEqualTo(0);   // nothing reserved any more

        InventoryItem item = repository.findByProductId(2L).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(4);
        assertThat(item.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void confirm_removesStockFromReservedOnly() {
        saveItem(3L, 1, 2);

        assertThat(repository.confirm(3L, 2, LocalDateTime.now())).isEqualTo(1);

        InventoryItem item = repository.findByProductId(3L).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(1);
        assertThat(item.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void adjust_neverAllowsNegativeAvailableStock() {
        saveItem(4L, 3, 0);

        assertThat(repository.adjust(4L, -5, LocalDateTime.now())).isEqualTo(0);
        assertThat(repository.adjust(4L, -3, LocalDateTime.now())).isEqualTo(1);

        assertThat(repository.findByProductId(4L).orElseThrow().getAvailableQuantity()).isEqualTo(0);
    }
}