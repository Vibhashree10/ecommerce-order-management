package com.ecommerce.order_service.controller;

import com.ecommerce.order_service.dto.response.OrderItemResponse;
import com.ecommerce.order_service.dto.response.OrderResponse;
import com.ecommerce.order_service.dto.response.OrderStatusHistoryResponse;
import com.ecommerce.order_service.dto.response.PageResponse;
import com.ecommerce.order_service.entity.OrderStatus;
import com.ecommerce.order_service.exception.ExternalServiceException;
import com.ecommerce.order_service.exception.InvalidOrderException;
import com.ecommerce.order_service.exception.InvalidOrderStateException;
import com.ecommerce.order_service.exception.ResourceNotFoundException;
import com.ecommerce.order_service.service.OrderService;
import com.ecommerce.order_service.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.ecommerce.order_service.support.TestSecurityConfig.API_KEY;
import static com.ecommerce.order_service.support.TestSecurityConfig.API_KEY_HEADER;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(TestSecurityConfig.class)
class OrderControllerTest {

    private static final String CREATE_BODY =
            "{\"customerId\":\"cust-1\",\"items\":[{\"productId\":1,\"quantity\":2,\"unitPrice\":10.50}]}";

    @Autowired private MockMvc mockMvc;
    @MockBean private OrderService orderService;

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse("ORD-1", "cust-1", status, new BigDecimal("21.00"), "USD", null,
                LocalDateTime.of(2030, 1, 1, 10, 0), LocalDateTime.of(2030, 1, 1, 10, 0),
                List.of(new OrderItemResponse(1L, null, null, new BigDecimal("10.50"), 2, new BigDecimal("21.00"))));
    }

    // ------------------------------------------------------------------ security

    @Test
    void withoutApiKey_returns401AndNeverReachesTheService() throws Exception {
        mockMvc.perform(get("/orders/ORD-1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(orderService);
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_returns201WithLocationWhenStockIsReserved() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(order(OrderStatus.STOCK_RESERVED));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/orders/ORD-1")))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$.status").value("STOCK_RESERVED"))
                .andExpect(jsonPath("$.totalAmount").value(21.00))
                .andExpect(jsonPath("$.items[0].lineTotal").value(21.00));
    }

    @Test
    void create_returns202WhenOrderIsStillPending() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(order(OrderStatus.PENDING));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void create_returns201ForRejectedOrderBecauseTheOrderWasRecorded() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(order(OrderStatus.REJECTED));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void create_forwardsTheIdempotencyKeyHeader() throws Exception {
        when(orderService.createOrder(any(), any())).thenReturn(order(OrderStatus.STOCK_RESERVED));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY).header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));

        verify(orderService).createOrder(any(), eq("key-1"));
    }

    @Test
    void create_rejectsInvalidBodyWith400() throws Exception {
        String invalid = "{\"customerId\":\"\",\"currency\":\"EURO\",\"items\":[{\"productId\":1,\"quantity\":0}]}";

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("customerId")))
                .andExpect(jsonPath("$.message").value(containsString("quantity")))
                .andExpect(jsonPath("$.message").value(containsString("currency")));
        verifyNoInteractions(orderService);
    }

    @Test
    void create_rejectsEmptyItemsWith400() throws Exception {
        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"c\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_mapsInvalidOrderTo400() throws Exception {
        when(orderService.createOrder(any(), any())).thenThrow(new InvalidOrderException("Product 1 does not exist"));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product 1 does not exist"));
    }

    @Test
    void create_mapsDownstreamFailureTo503() throws Exception {
        when(orderService.createOrder(any(), any())).thenThrow(new ExternalServiceException("product-service unreachable"));

        mockMvc.perform(post("/orders").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    // ------------------------------------------------------------------ read

    @Test
    void get_returnsTheOrder() throws Exception {
        when(orderService.getOrder("ORD-1")).thenReturn(order(OrderStatus.CONFIRMED));

        mockMvc.perform(get("/orders/ORD-1").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void get_returns404ForUnknownOrder() throws Exception {
        when(orderService.getOrder("NOPE")).thenThrow(new ResourceNotFoundException("Order NOPE not found"));

        mockMvc.perform(get("/orders/NOPE").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order NOPE not found"));
    }

    @Test
    void list_passesFiltersAndPaging() throws Exception {
        when(orderService.listOrders("cust-1", OrderStatus.CONFIRMED, 1, 5))
                .thenReturn(new PageResponse<>(List.of(order(OrderStatus.CONFIRMED)), 1, 5, 6, 2));

        mockMvc.perform(get("/orders").header(API_KEY_HEADER, API_KEY)
                        .param("customerId", "cust-1").param("status", "CONFIRMED").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void list_rejectsUnknownStatusWith400() throws Exception {
        mockMvc.perform(get("/orders").header(API_KEY_HEADER, API_KEY).param("status", "SHIPPED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void history_returnsTheAuditTrail() throws Exception {
        when(orderService.getHistory("ORD-1")).thenReturn(List.of(
                new OrderStatusHistoryResponse(null, OrderStatus.PENDING, "Order created", LocalDateTime.of(2030, 1, 1, 10, 0)),
                new OrderStatusHistoryResponse(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, null, LocalDateTime.of(2030, 1, 1, 10, 1))));

        mockMvc.perform(get("/orders/ORD-1/history").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].toStatus").value("STOCK_RESERVED"));
    }

    // ------------------------------------------------------------------ confirm / cancel

    @Test
    void confirm_returnsConfirmedOrder() throws Exception {
        when(orderService.confirmOrder("ORD-1")).thenReturn(order(OrderStatus.CONFIRMED));

        mockMvc.perform(post("/orders/ORD-1/confirm").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirm_mapsInvalidStateTo409() throws Exception {
        when(orderService.confirmOrder("ORD-1")).thenThrow(new InvalidOrderStateException("Order ORD-1 is CANCELLED"));

        mockMvc.perform(post("/orders/ORD-1/confirm").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("CANCELLED")));
    }

    @Test
    void cancel_withoutBodyUsesNoReason() throws Exception {
        when(orderService.cancelOrder(eq("ORD-1"), any())).thenReturn(order(OrderStatus.CANCELLED));

        mockMvc.perform(post("/orders/ORD-1/cancel").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(orderService).cancelOrder("ORD-1", null);
    }

    @Test
    void cancel_forwardsTheReason() throws Exception {
        when(orderService.cancelOrder(eq("ORD-1"), any())).thenReturn(order(OrderStatus.CANCELLED));

        mockMvc.perform(post("/orders/ORD-1/cancel").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isOk());
        verify(orderService).cancelOrder("ORD-1", "changed my mind");
    }

    @Test
    void unknownUrl_returns404NotA500() throws Exception {
        mockMvc.perform(get("/nothing-here").header(API_KEY_HEADER, API_KEY)).andExpect(status().isNotFound());
    }
}
