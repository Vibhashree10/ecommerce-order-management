package com.ecommerce.inventory_service.integration;

import com.ecommerce.inventory_service.repository.StockReservationRepository;
import com.ecommerce.inventory_service.scheduler.ReservationExpiryJob;
import com.ecommerce.inventory_service.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the whole service: real Spring context, the REAL SecurityConfig (API key + public health),
 * real Flyway migrations and a real PostgreSQL started by Testcontainers.
 * Skipped automatically when Docker is not running.
 *
 * Each test uses its own product ids / order ids because requests commit for real (no rollback).
 * MockMvc does not apply server.servlet.context-path, so URLs here have no /api prefix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "security.internal-api-key=it-key",
        "inventory.reservation.expiry-job-enabled=false",   // the expiry sweep is triggered by hand in the test
        "inventory.reservation.ttl-minutes=0"               // reservations are "expired" immediately
})
class InventoryApiIntegrationTest {

    private static final String KEY = "it-key";
    private static final String KEY_HEADER = "X-Internal-Api-Key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationService reservationService;
    @Autowired private StockReservationRepository reservationRepository;

    // ------------------------------------------------------------------ helpers
    private ResultActions post(String url, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions postNoBody(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url).header(KEY_HEADER, KEY));
    }

    private ResultActions put(String url, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put(url).header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions getJson(String url) throws Exception {
        return mockMvc.perform(get(url).header(KEY_HEADER, KEY));
    }

    private void createItem(long productId, int quantity, int reorderLevel) throws Exception {
        post("/inventory/items",
                "{\"productId\":" + productId + ",\"quantity\":" + quantity + ",\"reorderLevel\":" + reorderLevel + "}")
                .andExpect(status().isCreated());
    }

    private String reserveJson(String orderId, String itemsJson) {
        return "{\"orderId\":\"" + orderId + "\",\"items\":" + itemsJson + "}";
    }

    private String line(long productId, int quantity) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + "}";
    }

    private void assertStock(long productId, int available, int reserved) throws Exception {
        getJson("/inventory/items/" + productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(available))
                .andExpect(jsonPath("$.reservedQuantity").value(reserved));
    }

    // ------------------------------------------------------------------ platform
    @Test
    void flywayAppliedAllMigrationsToARealPostgres() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(3);

        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('inventory_items','stock_reservations','stock_movements')", Integer.class);
        assertThat(tables).isEqualTo(3);
    }

    @Test
    void healthIsPublic_butTheApiRequiresTheKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/inventory/items/1001")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/inventory/items/1001").header(KEY_HEADER, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/inventory/items/1001").header(KEY_HEADER, KEY)).andExpect(status().isNotFound());
    }

    @Test
    void unknownRoute_isA404_notA500() throws Exception {
        getJson("/inventory/no-such-endpoint").andExpect(status().isNotFound());
    }

    @Test
    void invalidRequests_areRejectedWith400() throws Exception {
        post("/inventory/items", "{\"quantity\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("productId")));
        post("/inventory/reservations", "{\"orderId\":\"X\",\"items\":[]}").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ stock management
    @Test
    void creatingTheSameProductTwice_isA409() throws Exception {
        createItem(1010, 5, 1);
        post("/inventory/items", "{\"productId\":1010,\"quantity\":5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    void adjustStock_neverGoesNegative_andLowStockIsReported() throws Exception {
        createItem(1008, 3, 0);

        put("/inventory/items/1008/stock", "{\"delta\":-5,\"reason\":\"damaged\"}").andExpect(status().isConflict());
        assertStock(1008, 3, 0);

        put("/inventory/items/1008/stock", "{\"delta\":-3,\"reason\":\"damaged\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(0))
                .andExpect(jsonPath("$.lowStock").value(true));

        getJson("/inventory/items/low-stock?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].productId", hasItem(1008)));

        getJson("/inventory/items/1008/movements")
                .andExpect(jsonPath("$.content[*].type", hasItems("INITIAL", "ADJUSTMENT")));
    }

    @Test
    void availabilityCheck_reflectsStock() throws Exception {
        createItem(1011, 4, 0);
        getJson("/inventory/items/1011/availability?quantity=4").andExpect(jsonPath("$.sufficient").value(true));
        getJson("/inventory/items/1011/availability?quantity=5").andExpect(jsonPath("$.sufficient").value(false));
    }

    // ------------------------------------------------------------------ reservation saga
    @Test
    void reserveThenConfirm_movesStockOutOfReserved_andWritesTheLedger() throws Exception {
        createItem(1002, 10, 1);

        post("/inventory/reservations", reserveJson("ORD-A", "[" + line(1002, 4) + "]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertStock(1002, 6, 4);

        postNoBody("/inventory/reservations/ORD-A/confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertStock(1002, 6, 0);

        // replaying confirm is safe and changes nothing
        postNoBody("/inventory/reservations/ORD-A/confirm").andExpect(status().isOk());
        assertStock(1002, 6, 0);

        getJson("/inventory/items/1002/movements")
                .andExpect(jsonPath("$.content[*].type", hasItems("INITIAL", "RESERVE", "CONFIRM")));
    }

    @Test
    void release_givesTheStockBack_andIsIdempotent() throws Exception {
        createItem(1003, 5, 0);
        post("/inventory/reservations", reserveJson("ORD-B", "[" + line(1003, 3) + "]")).andExpect(status().isCreated());
        assertStock(1003, 2, 3);

        postNoBody("/inventory/reservations/ORD-B/release")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        assertStock(1003, 5, 0);

        postNoBody("/inventory/reservations/ORD-B/release").andExpect(status().isOk());
        assertStock(1003, 5, 0);   // not released twice
    }

    @Test
    void confirmingAReleasedReservation_isA409() throws Exception {
        createItem(1012, 5, 0);
        post("/inventory/reservations", reserveJson("ORD-C", "[" + line(1012, 1) + "]")).andExpect(status().isCreated());
        postNoBody("/inventory/reservations/ORD-C/release").andExpect(status().isOk());

        postNoBody("/inventory/reservations/ORD-C/confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("RELEASED")));
        assertStock(1012, 5, 0);
    }

    @Test
    void releasingAConfirmedReservation_isA409_andStockStaysSold() throws Exception {
        createItem(1013, 5, 0);
        post("/inventory/reservations", reserveJson("ORD-D", "[" + line(1013, 2) + "]")).andExpect(status().isCreated());
        postNoBody("/inventory/reservations/ORD-D/confirm").andExpect(status().isOk());

        postNoBody("/inventory/reservations/ORD-D/release").andExpect(status().isConflict());
        assertStock(1013, 3, 0);
    }

    @Test
    void reserveIsIdempotent_theSameOrderIsNeverReservedTwice() throws Exception {
        createItem(1014, 10, 0);
        String body = reserveJson("ORD-E", "[" + line(1014, 2) + "]");

        post("/inventory/reservations", body).andExpect(status().isCreated());
        post("/inventory/reservations", body).andExpect(status().isCreated());   // replay returns the same reservation

        assertStock(1014, 8, 2);
    }

    @Test
    void reserveIsAllOrNothing_whenOneLineHasNotEnoughStock() throws Exception {
        createItem(1004, 5, 0);
        createItem(1005, 1, 0);

        post("/inventory/reservations", reserveJson("ORD-AON", "[" + line(1004, 2) + "," + line(1005, 3) + "]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Insufficient stock for product 1005")));

        assertStock(1004, 5, 0);   // product 1004 was rolled back
        assertStock(1005, 1, 0);
        getJson("/inventory/reservations/ORD-AON").andExpect(status().isNotFound());
    }

    @Test
    void reservingAProductWithoutInventory_isA404() throws Exception {
        post("/inventory/reservations", reserveJson("ORD-F", "[" + line(999999, 1) + "]"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateLinesOfTheSameProduct_areMerged() throws Exception {
        createItem(1015, 10, 0);
        post("/inventory/reservations", reserveJson("ORD-G", "[" + line(1015, 2) + "," + line(1015, 3) + "]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
        assertStock(1015, 5, 5);
    }

    // ------------------------------------------------------------------ concurrency + expiry
    @Test
    void concurrentReservations_neverOversell() throws Exception {
        createItem(1006, 5, 0);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String orderId = "RACE-" + i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(MockMvcRequestBuilders.post("/inventory/reservations")
                                .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                                .content(reserveJson(orderId, "[" + line(1006, 1) + "]")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        start.countDown();

        int created = 0;
        int conflicts = 0;
        for (Future<Integer> result : results) {
            int httpStatus = result.get(60, TimeUnit.SECONDS);
            if (httpStatus == 201) {
                created++;
            } else if (httpStatus == 409) {
                conflicts++;
            }
        }
        pool.shutdown();

        assertThat(created).as("exactly the 5 units in stock can be reserved").isEqualTo(5);
        assertThat(conflicts).as("all other requests are refused, never a 500").isEqualTo(15);
        assertStock(1006, 0, 5);
    }

    @Test
    void expiredReservationsAreReleasedByTheExpiryJob() throws Exception {
        createItem(1007, 5, 0);
        post("/inventory/reservations", reserveJson("ORD-EXP", "[" + line(1007, 3) + "]")).andExpect(status().isCreated());
        assertStock(1007, 2, 3);

        Thread.sleep(100);   // ttl-minutes=0, so the reservation is already past its expiry time
        new ReservationExpiryJob(reservationRepository, reservationService).releaseExpiredReservations();

        getJson("/inventory/reservations/ORD-EXP").andExpect(jsonPath("$.status").value("EXPIRED"));
        assertStock(1007, 5, 0);

        // an order that was already confirmed must never be expired
        createItem(1016, 5, 0);
        post("/inventory/reservations", reserveJson("ORD-KEEP", "[" + line(1016, 2) + "]")).andExpect(status().isCreated());
        postNoBody("/inventory/reservations/ORD-KEEP/confirm").andExpect(status().isOk());
        Thread.sleep(100);
        new ReservationExpiryJob(reservationRepository, reservationService).releaseExpiredReservations();
        getJson("/inventory/reservations/ORD-KEEP").andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertStock(1016, 3, 0);
    }
}