package com.ecommerce.product_service.repository;

import com.ecommerce.product_service.entity.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
public class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    private Category category;

    @BeforeEach
    void setUp() {
        category = Category.builder()
                .name("Electronics")
                .description("Electronic products")
                .active(true)
                .build();
        category = categoryRepository.save(category);
    }

    @Test
    void testFindByName_Success() {
        Optional<Category> result = categoryRepository.findByName("Electronics");

        assertTrue(result.isPresent());
        assertEquals("Electronics", result.get().getName());
    }

    @Test
    void testFindByName_NotFound() {
        Optional<Category> result = categoryRepository.findByName("Invalid");

        assertFalse(result.isPresent());
    }

    @Test
    void testFindAllActive() {
        var results = categoryRepository.findAllActive();

        assertNotNull(results);
        assertTrue(results.stream().anyMatch(c -> c.getId().equals(category.getId())));
    }

    @Test
    void testCountByActive() {
        long count = categoryRepository.countByActive(true);

        assertTrue(count > 0);
    }
}
