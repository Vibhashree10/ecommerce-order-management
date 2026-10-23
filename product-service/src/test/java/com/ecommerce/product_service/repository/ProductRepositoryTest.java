package com.ecommerce.product_service.repository;

import com.ecommerce.product_service.entity.Category;
import com.ecommerce.product_service.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
public class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Category category;
    private Product product;

    @BeforeEach
    void setUp() {
        category = Category.builder()
                .name("Electronics")
                .description("Electronic products")
                .active(true)
                .build();
        category = categoryRepository.save(category);

        product = Product.builder()
                .sku("PROD-001")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .quantity(100)
                .category(category)
                .active(true)
                .build();
        product = productRepository.save(product);
    }

    @Test
    void testFindBySku_Success() {
        Optional<Product> result = productRepository.findBySku("PROD-001");

        assertTrue(result.isPresent());
        assertEquals("PROD-001", result.get().getSku());
    }

    @Test
    void testFindBySku_NotFound() {
        Optional<Product> result = productRepository.findBySku("INVALID-SKU");

        assertFalse(result.isPresent());
    }

    @Test
    void testFindByCategoryId() {
        List<Product> results = productRepository.findByCategoryId(category.getId());

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("PROD-001", results.get(0).getSku());
    }

    @Test
    void testFindLowStockProducts() {
        Product lowStockProduct = Product.builder()
                .sku("PROD-002")
                .name("Low Stock Product")
                .price(BigDecimal.valueOf(49.99))
                .quantity(5)
                .category(category)
                .active(true)
                .build();
        productRepository.save(lowStockProduct);

        List<Product> results = productRepository.findLowStockProducts(10);

        assertTrue(results.stream().anyMatch(p -> p.getSku().equals("PROD-002")));
    }
}
