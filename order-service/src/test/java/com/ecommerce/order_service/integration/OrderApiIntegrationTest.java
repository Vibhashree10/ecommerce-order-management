package com.ecommerce.order_service.integration;

import com.ecommerce.order_service.client.InventoryClient;
import com.ecommerce.order_service.client.ProductClient;
import com.ecommerce.order_service.client.ProductInfo;
import com.ecommerce.order_service.client.ReservationInfo;
import com.ecommerce.order_service.exception.ExternalServiceException;
import com.ecommerce.order_service.exception.ReservationStateException;
import com.ecommerce.order_service.exception.StockUnavailableException;
import com.ecommerce.order_service.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the whole service: real Spring context, the REAL SecurityConfig (API key + public health),
 * real Flyway migrations and a real PostgreSQL started by Testcontainers. inventory-service and product-service are
 * replaced by mocks (their HTTP mapping is covered by InventoryClientTest / ProductClientTest).
 * Skipped automatically when Docker is not running.
 *
 * Each test uses its own customer id because requests commit for real (no rollback).
 * MockMvc does not apply server.servlet.context-path, so URLs here have no /api prefix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "security.internal-api-key=it-key",
        "orders.jobs-enabled=false"        // recovery is triggered by hand in the tests
})
class OrderApiIntegrationTest {

    private static final String KEY = "it-key";
    private static final String KEY_HEADER = "X-Internal-Api-Key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderService orderService;
    @MockBean private InventoryClient inventoryClient;
    @MockBean private ProductClient productClient;

    // ------------------------------------------------------------------ helpers

    private static String customer() {
        return "cust-" + UUID.randomUUID();
    }

    private static String body(String customerId, long productId, int qty, String price) {
        return "{\"customerId\":\"" + customerId + "\",\"items\":[{\"productId\":" + productId
                + ",\"quantity\":" + qty + ",\"unitPrice\":" + price + "}]}";
    }

    private ResultActions create(String json, String idempotencyKey) throws Exception {
        var builder = MockMvcRequestBuilders.post("/orders").header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions postNoBody(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url).header(KEY_HEADER, KEY));
    }

    private ResultActions getWithKey(String url) throws Exception {
        return mockMvc.perform(get(url).header(KEY_HEADER, KEY));
    }

    private String createdOrderNumber(ResultActions result) throws Exception {
        MvcResult r = result.andReturn();
        JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
        return json.get("orderNumber").asText();
    }

    private long historyRows(String orderNumber) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_history h JOIN orders o ON o.id = h.order_id WHERE o.order_number = ?",
                Long.class, orderNumber);
        return count == null ? 0 : count;
    }

    private static ReservationInfo reservation(String status) {
        return new ReservationInfo("x", status, null);
    }

    // ------------------------------------------------------------------ security / health

    @Test
    void healthIsPublicButOrdersNeedTheApiKey() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/orders/ORD-X")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/orders/ORD-X").header(KEY_HEADER, "wrong")).andExpect(status().isUnauthorized());
        getWithKey("/orders/ORD-X").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void fullLifecycle_createThenConfirm_withAuditTrail() throws Exception {
        String cust = customer();

        ResultActions created = create(body(cust, 10, 2, "12.50"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STOCK_RESERVED"))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(25.00));
        String number = createdOrderNumber(created);
        verify(inventoryClient).reserve(anyString(), anyList());

        postNoBody("/orders/" + number + "/confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(inventoryClient).confirm(number);

        // confirming again is a harmless replay: no second call to inventory
        postNoBody("/orders/" + number + "/confirm").andExpect(status().isOk());
        verify(inventoryClient, times(1)).confirm(number);

        getWithKey("/orders/" + number + "/history")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].toStatus").value("STOCK_RESERVED"))
                .andExpect(jsonPath("$[2].toStatus").value("CONFIRMED"));

        getWithKey("/orders?customerId=" + cust + "&status=CONFIRMED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderNumber").value(number))
                .andExpect(jsonPath("$.content[0].items.length()").value(1));
    }

    @Test
    void cancel_releasesStockAndCannotBeUndone() throws Exception {
        String number = createdOrderNumber(create(body(customer(), 11, 1, "3.00"), null).andExpect(status().isCreated()));

        mockMvc.perform(MockMvcRequestBuilders.post("/orders/" + number + "/cancel").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failureReason").value("changed my mind"));
        verify(inventoryClient).release(number);

        postNoBody("/orders/" + number + "/cancel").andExpect(status().isOk());      // idempotent
        postNoBody("/orders/" + number + "/confirm").andExpect(status().isConflict()); // cannot confirm a cancelled order
    }

    @Test
    void confirmedOrderCannotBeCancelled() throws Exception {
        String number = createdOrderNumber(create(body(customer(), 12, 1, "3.00"), null));
        postNoBody("/orders/" + number + "/confirm").andExpect(status().isOk());

        postNoBody("/orders/" + number + "/cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("CONFIRMED")));
        verify(inventoryClient, never()).release(number);
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void createWithTheSameIdempotencyKeyReturnsTheSameOrderAndReservesOnce() throws Exception {
        String cust = customer();
        String json = body(cust, 13, 1, "9.99");

        String first = createdOrderNumber(create(json, "key-abc").andExpect(status().isCreated()));
        String second = createdOrderNumber(create(json, "key-abc").andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        verify(inventoryClient, times(1)).reserve(anyString(), anyList());
        getWithKey("/orders?customerId=" + cust).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void sameKeyForADifferentCustomerCreatesADifferentOrder() throws Exception {
        String a = createdOrderNumber(create(body(customer(), 14, 1, "1.00"), "shared-key"));
        String b = createdOrderNumber(create(body(customer(), 14, 1, "1.00"), "shared-key"));

        assertThat(a).isNotEqualTo(b);
    }

    // ------------------------------------------------------------------ failure paths of the Saga

    @Test
    void outOfStock_createsARejectedOrderWithTheReason() throws Exception {
        when(inventoryClient.reserve(anyString(), anyList()))
                .thenThrow(new StockUnavailableException("Insufficient stock for product 15: requested 99, available 3"));

        String number = createdOrderNumber(create(body(customer(), 15, 99, "1.00"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason").value(containsString("Insufficient stock"))));

        postNoBody("/orders/" + number + "/confirm").andExpect(status().isConflict());
        postNoBody("/orders/" + number + "/cancel").andExpect(status().isConflict());
        assertThat(historyRows(number)).isEqualTo(2);   // created, rejected
    }

    @Test
    void inventoryDown_orderStaysPendingThenRecoveryReservesIt() throws Exception {
        when(inventoryClient.reserve(anyString(), anyList())).thenThrow(new ExternalServiceException("inventory-service unreachable"));

        String number = createdOrderNumber(create(body(customer(), 16, 1, "5.00"), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING")));

        postNoBody("/orders/" + number + "/confirm").andExpect(status().isConflict());   // not confirmable while PENDING

        // inventory-service comes back; the recovery job calls this for each stale PENDING order
        org.mockito.Mockito.reset(inventoryClient);
        doReturn(reservation("PENDING")).when(inventoryClient).reserve(anyString(), anyList());
        orderService.retryPendingOrder(number);

        getWithKey("/orders/" + number).andExpect(jsonPath("$.status").value("STOCK_RESERVED"));
        postNoBody("/orders/" + number + "/confirm").andExpect(status().isOk());
    }

    @Test
    void confirmFailsAndCancelsTheOrderWhenTheReservationExpired() throws Exception {
        String number = createdOrderNumber(create(body(customer(), 17, 1, "5.00"), null));
        doThrow(new ReservationStateException("Reservation for order is EXPIRED")).when(inventoryClient).confirm(number);
        when(inventoryClient.get(number)).thenReturn(Optional.of(reservation("EXPIRED")));

        postNoBody("/orders/" + number + "/confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("no longer valid")));

        getWithKey("/orders/" + number).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void inventoryDownDuringConfirm_returns503AndLeavesTheOrderConfirmable() throws Exception {
        String number = createdOrderNumber(create(body(customer(), 18, 1, "5.00"), null));
        doThrow(new ExternalServiceException("inventory-service unreachable")).when(inventoryClient).confirm(number);

        postNoBody("/orders/" + number + "/confirm").andExpect(status().isServiceUnavailable());
        getWithKey("/orders/" + number).andExpect(jsonPath("$.status").value("STOCK_RESERVED"));

        org.mockito.Mockito.reset(inventoryClient);
        postNoBody("/orders/" + number + "/confirm").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ product lookup

    @Test
    void withProductLookup_theCatalogPriceIsUsedAndUnknownProductsAreRejected() throws Exception {
        when(productClient.isEnabled()).thenReturn(true);
        when(productClient.find(20L)).thenReturn(Optional.of(new ProductInfo(20L, "SKU-20", "Monitor", new BigDecimal("199.00"), true)));
        when(productClient.find(21L)).thenReturn(Optional.empty());

        create("{\"customerId\":\"" + customer() + "\",\"items\":[{\"productId\":20,\"quantity\":2,\"unitPrice\":0.01}]}", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(398.00))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-20"))
                .andExpect(jsonPath("$.items[0].productName").value("Monitor"));

        create("{\"customerId\":\"" + customer() + "\",\"items\":[{\"productId\":21,\"quantity\":1}]}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not exist")));
    }

    @Test
    void withoutProductLookup_missingUnitPriceIsABadRequest() throws Exception {
        create("{\"customerId\":\"" + customer() + "\",\"items\":[{\"productId\":22,\"quantity\":1}]}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("unitPrice is required")));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void confirmRacingWithCancel_exactlyOneWins() throws Exception {
        String number = createdOrderNumber(create(body(customer(), 30, 1, "5.00"), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> confirm = pool.submit(() -> {
                go.await();
                return postNoBody("/orders/" + number + "/confirm").andReturn().getResponse().getStatus();
            });
            Future<Integer> cancel = pool.submit(() -> {
                go.await();
                return postNoBody("/orders/" + number + "/cancel").andReturn().getResponse().getStatus();
            });
            go.countDown();
            int confirmStatus = confirm.get(30, TimeUnit.SECONDS);
            int cancelStatus = cancel.get(30, TimeUnit.SECONDS);

            assertThat(new int[]{confirmStatus, cancelStatus}).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        String finalStatus = jdbcTemplate.queryForObject("SELECT status FROM orders WHERE order_number = ?", String.class, number);
        assertThat(finalStatus).isIn("CONFIRMED", "CANCELLED");
        assertThat(historyRows(number)).isEqualTo(3);   // created, stock reserved, exactly one final transition
    }
}
