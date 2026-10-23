package com.ecommerce.product_service.integration;

import com.ecommerce.product_service.event.EventPublisher;
import com.ecommerce.product_service.repository.CategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
public class CategoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean // <--- This prevents Spring from trying to connect to a real Kafka broker
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
    }

    @Test
    void testCreateAndRetrieveCategory() throws Exception {
        mockMvc.perform(post("/api/category")
                        .contextPath("/api")
                .param("name", "Test Category")
                .param("description", "Test Description"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Category"));

        mockMvc.perform(get("/api/category/categories")
                .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void testGetAllActiveCategories() throws Exception {
        mockMvc.perform(post("/api/category")
                        .contextPath("/api")
                .param("name", "Category 1")
                .param("description", "Description 1"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/category/active/all")
                .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
