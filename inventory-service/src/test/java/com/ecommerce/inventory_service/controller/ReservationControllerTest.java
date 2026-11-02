package com.ecommerce.inventory_service.controller;

import com.ecommerce.inventory_service.dto.request.ReserveStockRequest;
import com.ecommerce.inventory_service.dto.response.ReservationItemResponse;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;
import com.ecommerce.inventory_service.entity.ReservationStatus;
import com.ecommerce.inventory_service.exception.InsufficientStockException;
import com.ecommerce.inventory_service.exception.InvalidReservationStateException;
import com.ecommerce.inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.inventory_service.service.ReservationService;
import com.ecommerce.inventory_service.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static com.ecommerce.inventory_service.support.TestSecurityConfig.API_KEY;
import static com.ecommerce.inventory_service.support.TestSecurityConfig.API_KEY_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saga-participant API used by order-service: reserve, confirm, release, get. */
@WebMvcTest(ReservationController.class)
@Import(TestSecurityConfig.class)
class ReservationControllerTest {

    private static final String RESERVE_BODY =
            "{\"orderId\":\"ORD-1\",\"items\":[{\"productId\":1,\"quantity\":2}]}";

    @Autowired private MockMvc mockMvc;
    @MockBean private ReservationService reservationService;

    private ReservationResponse reservation(ReservationStatus status) {
        return new ReservationResponse("ORD-1", status, LocalDateTime.of(2030, 1, 1, 10, 0),
                List.of(new ReservationItemResponse(1L, 2)));
    }

    @Test
    void reserve_returns201WithPendingReservation() throws Exception {
        when(reservationService.reserve(any(ReserveStockRequest.class))).thenReturn(reservation(ReservationStatus.PENDING));

        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ORD-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        ArgumentCaptor<ReserveStockRequest> captor = ArgumentCaptor.forClass(ReserveStockRequest.class);
        verify(reservationService).reserve(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo("ORD-1");
        assertThat(captor.getValue().items()).hasSize(1);
    }

    @Test
    void reserve_returns400WhenItemsAreEmpty() throws Exception {
        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("items")));

        verifyNoInteractions(reservationService);
    }

    @Test
    void reserve_returns400WhenAnItemQuantityIsNotPositive() throws Exception {
        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationService);
    }

    @Test
    void reserve_returns400WhenOrderIdIsBlank() throws Exception {
        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"\",\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("orderId")));
    }

    @Test
    void reserve_returns409WhenStockIsInsufficient() throws Exception {
        when(reservationService.reserve(any(ReserveStockRequest.class)))
                .thenThrow(new InsufficientStockException(1L, 2, 0));

        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("Insufficient stock for product 1")));
    }

    @Test
    void reserve_returns404WhenProductHasNoInventory() throws Exception {
        when(reservationService.reserve(any(ReserveStockRequest.class)))
                .thenThrow(new ResourceNotFoundException("No inventory record for product 1"));

        mockMvc.perform(post("/inventory/reservations").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirm_returns200AndCallsServiceWithOrderId() throws Exception {
        when(reservationService.confirm("ORD-1")).thenReturn(reservation(ReservationStatus.CONFIRMED));

        mockMvc.perform(post("/inventory/reservations/ORD-1/confirm").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(reservationService).confirm("ORD-1");
    }

    @Test
    void confirm_returns409WhenReservationWasAlreadyReleased() throws Exception {
        when(reservationService.confirm("ORD-1"))
                .thenThrow(new InvalidReservationStateException("Reservation for order ORD-1 is RELEASED and cannot become CONFIRMED"));

        mockMvc.perform(post("/inventory/reservations/ORD-1/confirm").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("RELEASED")));
    }

    @Test
    void release_returns200AndCallsServiceWithOrderId() throws Exception {
        when(reservationService.release("ORD-1")).thenReturn(reservation(ReservationStatus.RELEASED));

        mockMvc.perform(post("/inventory/reservations/ORD-1/release").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        verify(reservationService).release("ORD-1");
    }

    @Test
    void get_returns200WithReservation() throws Exception {
        when(reservationService.get("ORD-1")).thenReturn(reservation(ReservationStatus.PENDING));

        mockMvc.perform(get("/inventory/reservations/ORD-1").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-1"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void get_returns404ForUnknownOrder() throws Exception {
        when(reservationService.get("ORD-404")).thenThrow(new ResourceNotFoundException("No reservation found for order ORD-404"));

        mockMvc.perform(get("/inventory/reservations/ORD-404").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyReservationEndpoint_returns401WithoutApiKey() throws Exception {
        mockMvc.perform(post("/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVE_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/inventory/reservations/ORD-1/confirm")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/inventory/reservations/ORD-1/release")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/inventory/reservations/ORD-1")).andExpect(status().isUnauthorized());

        verifyNoInteractions(reservationService);
    }
}