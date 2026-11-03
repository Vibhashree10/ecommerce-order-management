package com.ecommerce.notification_service.controller;

import com.ecommerce.notification_service.dto.request.OrderEventMessage;
import com.ecommerce.notification_service.dto.response.NotificationResponse;
import com.ecommerce.notification_service.dto.response.PageResponse;
import com.ecommerce.notification_service.entity.ChannelType;
import com.ecommerce.notification_service.entity.NotificationStatus;
import com.ecommerce.notification_service.exception.GlobalExceptionHandler;
import com.ecommerce.notification_service.exception.InvalidNotificationStateException;
import com.ecommerce.notification_service.exception.ResourceNotFoundException;
import com.ecommerce.notification_service.service.NotificationService;
import com.ecommerce.notification_service.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockBean NotificationService service;

    private NotificationResponse response() {
        return new NotificationResponse(1L, ChannelType.LOG, "ORDER_CREATED", "ORD-1", "c1", null, "s", "b",
                NotificationStatus.SENT, 1, null, null, null);
    }

    @Test
    void withoutApiKey_is401() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void get_returnsTheNotification() throws Exception {
        when(service.get(1L)).thenReturn(response());
        mvc.perform(get("/notifications/1").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1"));
    }

    @Test
    void get_unknown_is404() throws Exception {
        when(service.get(9L)).thenThrow(new ResourceNotFoundException("Notification 9 not found"));
        mvc.perform(get("/notifications/9").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void retry_wrongState_is409() throws Exception {
        when(service.retry(1L)).thenThrow(new InvalidNotificationStateException("only FAILED"));
        mvc.perform(post("/notifications/1/retry").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void list_passesFilters() throws Exception {
        when(service.list(eq("ORD-1"), any(), eq(NotificationStatus.SENT), eq(0), eq(20)))
                .thenReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1));
        mvc.perform(get("/notifications?orderNumber=ORD-1&status=SENT").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    void list_badStatus_is400() throws Exception {
        mvc.perform(get("/notifications?status=NOPE").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postEvent_acceptsValidEvent_andRejectsMissingFields() throws Exception {
        when(service.handleEvent(any(OrderEventMessage.class))).thenReturn(List.of(response()));
        mvc.perform(post("/notifications/events").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"ORDER_CREATED\",\"orderNumber\":\"ORD-1\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/notifications/events").header(TestSecurityConfig.API_KEY_HEADER, TestSecurityConfig.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"ORDER_CREATED\"}"))
                .andExpect(status().isBadRequest());
    }
}
