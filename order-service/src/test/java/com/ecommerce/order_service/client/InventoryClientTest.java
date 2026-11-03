package com.ecommerce.order_service.client;

import com.ecommerce.order_service.exception.ExternalServiceException;
import com.ecommerce.order_service.exception.ReservationNotFoundException;
import com.ecommerce.order_service.exception.ReservationStateException;
import com.ecommerce.order_service.exception.StockUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Order -> Inventory inter-service call against a mock HTTP server (no real network).
 * Main point: the same HTTP status means different things per operation (409 on reserve = no stock, 409 on confirm = wrong state).
 */
class InventoryClientTest {

    private static final String RESERVATION_PENDING =
            "{\"orderId\":\"ORD-1\",\"status\":\"PENDING\",\"expiresAt\":\"2030-01-01T10:00:00\",\"items\":[{\"productId\":1,\"quantity\":2}]}";

    private MockRestServiceServer server;

    private InventoryClient client(boolean enabled) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory.test/api");
        server = MockRestServiceServer.bindTo(builder).build();
        return new InventoryClient(builder.build(), enabled);
    }

    private static String reservation(String status) {
        return "{\"orderId\":\"ORD-1\",\"status\":\"" + status + "\",\"expiresAt\":\"2030-01-01T10:00:00\",\"items\":[]}";
    }

    // ------------------------------------------------------------------ reserve

    @Test
    void reserve_postsTheOrderNumberAsOrderIdAndReturnsTheReservation() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"orderId\":\"ORD-1\",\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(RESERVATION_PENDING));

        ReservationInfo info = client.reserve("ORD-1", List.of(new ReserveItem(1L, 2)));

        assertThat(info.isPending()).isTrue();
        assertThat(info.orderId()).isEqualTo("ORD-1");
        server.verify();
    }

    @Test
    void reserve_409meansNotEnoughStockAndCarriesInventorysMessage() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":409,\"message\":\"Insufficient stock for product 1: requested 5, available 2\"}"));

        assertThatThrownBy(() -> client.reserve("ORD-1", List.of(new ReserveItem(1L, 5))))
                .isInstanceOf(StockUnavailableException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    void reserve_404meansProductIsNotStocked() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"No inventory record for product 9\"}"));

        assertThatThrownBy(() -> client.reserve("ORD-1", List.of(new ReserveItem(9L, 1))))
                .isInstanceOf(StockUnavailableException.class)
                .hasMessageContaining("No inventory record");
    }

    @Test
    void reserve_401isATechnicalProblemNotAStockProblem() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.reserve("ORD-1", List.of(new ReserveItem(1L, 1))))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void reserve_serverErrorIsATechnicalProblem() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations")).andRespond(withServerError());

        assertThatThrownBy(() -> client.reserve("ORD-1", List.of(new ReserveItem(1L, 1))))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void reserve_replayOfAReleasedReservationIsNotTreatedAsStockHeld() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations"))
                .andRespond(withSuccess(reservation("RELEASED"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.reserve("ORD-1", List.of(new ReserveItem(1L, 1))))
                .isInstanceOf(ReservationStateException.class)
                .hasMessageContaining("RELEASED");
    }

    // ------------------------------------------------------------------ confirm / release / get

    @Test
    void confirm_returnsConfirmedReservation() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(reservation("CONFIRMED"), MediaType.APPLICATION_JSON));

        assertThat(client.confirm("ORD-1").isConfirmed()).isTrue();
        server.verify();
    }

    @Test
    void confirm_404meansReservationNotFound() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1/confirm"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.confirm("ORD-1")).isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void confirm_409meansReservationIsInAnotherState() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1/confirm"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Reservation for order ORD-1 is EXPIRED\"}"));

        assertThatThrownBy(() -> client.confirm("ORD-1"))
                .isInstanceOf(ReservationStateException.class)
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void release_returnsReleasedReservation() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1/release"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(reservation("RELEASED"), MediaType.APPLICATION_JSON));

        assertThat(client.release("ORD-1").status()).isEqualTo("RELEASED");
        server.verify();
    }

    @Test
    void get_returnsTheReservation() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(RESERVATION_PENDING, MediaType.APPLICATION_JSON));

        Optional<ReservationInfo> info = client.get("ORD-1");

        assertThat(info).isPresent();
        assertThat(info.get().expiresAt()).isEqualTo("2030-01-01T10:00:00");
    }

    @Test
    void get_404returnsEmpty() {
        InventoryClient client = client(true);
        server.expect(requestTo("http://inventory.test/api/inventory/reservations/ORD-1")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.get("ORD-1")).isEmpty();
    }

    // ------------------------------------------------------------------ disabled

    @Test
    void whenDisabled_aStubAnswersAndNoHttpCallIsMade() {
        InventoryClient client = client(false);

        assertThat(client.reserve("ORD-1", List.of(new ReserveItem(1L, 1))).isPending()).isTrue();
        assertThat(client.confirm("ORD-1").isConfirmed()).isTrue();
        assertThat(client.release("ORD-1").status()).isEqualTo("RELEASED");
        server.verify();   // no expectations were set: any HTTP call would have failed
    }
}
