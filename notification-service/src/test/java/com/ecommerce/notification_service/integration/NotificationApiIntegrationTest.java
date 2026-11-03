package com.ecommerce.notification_service.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole application (real security chain, Flyway, PostgreSQL). Skipped automatically without Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@TestPropertySource(properties = "security.internal-api-key=it-key")
class NotificationApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    private static final String KEY = "X-Internal-Api-Key";

    @Test
    void eventBecomesASentLogNotification_andReplayCreatesNothingNew() throws Exception {
        String body = "{\"eventType\":\"ORDER_CONFIRMED\",\"orderNumber\":\"ORD-IT-1\",\"customerId\":\"c1\"}";

        mvc.perform(post("/notifications/events").header(KEY, "it-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].channel").value("LOG"))
                .andExpect(jsonPath("$[0].status").value("SENT"));

        mvc.perform(post("/notifications/events").header(KEY, "it-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/notifications?orderNumber=ORD-IT-1").header(KEY, "it-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void security_healthIsPublic_apiNeedsKey() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications").header(KEY, "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void retryOfSentNotification_is409_andUnknownIs404() throws Exception {
        mvc.perform(post("/notifications/events").header(KEY, "it-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"ORDER_CREATED\",\"orderNumber\":\"ORD-IT-2\"}"))
                .andExpect(status().isAccepted());
        String id = "1";
        mvc.perform(get("/notifications/999999").header(KEY, "it-key")).andExpect(status().isNotFound());
        mvc.perform(post("/notifications/" + id + "/retry").header(KEY, "it-key")).andExpect(status().is4xxClientError());
    }
}
