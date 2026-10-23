package com.ecommerce.product_service.integration;

import com.ecommerce.product_service.dto.request.CreateProductRequest;
import com.ecommerce.product_service.entity.Category;
import com.ecommerce.product_service.event.EventPublisher;
import com.ecommerce.product_service.repository.CategoryRepository;
import com.ecommerce.product_service.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
public class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Category category;

    @MockBean // <--- This prevents Spring from trying to connect to a real Kafka broker
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();

        category = Category.builder()
                .name("Electronics")
                .description("Electronic products")
                .active(true)
                .build();
        category = categoryRepository.save(category);
    }

    @Test
    void testCreateAndRetrieveProduct() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("INT-PROD-001")
                .name("Integration Test Product")
                .description("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .quantity(50)
                .categoryId(category.getId())
                .build();

        mockMvc.perform(post("/api/products")
                        .contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("INT-PROD-001"));

        mockMvc.perform(get("/api/products/sku/INT-PROD-001")
                .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Integration Test Product"));
    }

    @Test
    void testGetAllProducts() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .sku("INT-PROD-002")
                .name("Test Product 2")
                .price(BigDecimal.valueOf(49.99))
                .quantity(25)
                .categoryId(category.getId())
                .build();

        mockMvc.perform(post("/api/products")
                        .contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/products")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }
}
