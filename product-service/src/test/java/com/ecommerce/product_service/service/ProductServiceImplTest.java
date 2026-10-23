package com.ecommerce.product_service.service;

import com.ecommerce.product_service.dto.request.CreateProductRequest;
import com.ecommerce.product_service.dto.response.CategoryResponse;
import com.ecommerce.product_service.dto.response.ProductResponse;
import com.ecommerce.product_service.entity.Category;
import com.ecommerce.product_service.entity.Product;
import com.ecommerce.product_service.event.EventPublisher;
import com.ecommerce.product_service.exception.ResourceNotFoundException;
import com.ecommerce.product_service.repository.CategoryRepository; // <-- Import added
import com.ecommerce.product_service.repository.ProductRepository;
import com.ecommerce.product_service.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@AutoConfigureMockMvc(addFilters = false)
public class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository; // <-- Added missing mock

    @Mock
    private CategoryService categoryService;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product;
    private Category category; // <-- Extracted for reuse
    private CreateProductRequest createProductRequest;
    private CategoryResponse categoryResponse;

    @BeforeEach
    void setUp() {
        category = Category.builder()
                .id(1L)
                .name("Electronics")
                .active(true)
                .build();

        product = Product.builder()
                .id(1L)
                .sku("PROD-001")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .quantity(100)
                .category(category)
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        createProductRequest = CreateProductRequest.builder()
                .sku("PROD-001")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .quantity(100)
                .categoryId(1L)
                .build();

        categoryResponse = CategoryResponse.builder()
                .id(1L)
                .name("Electronics")
                .active(true)
                .build();
    }

    @Test
    void testGetProductById_Success() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(categoryService.getCategoryById(1L)).thenReturn(categoryResponse);

        ProductResponse result = productService.getProductById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("PROD-001", result.getSku());
        verify(productRepository, times(1)).findById(1L);
    }

    @Test
    void testGetProductById_NotFound() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.getProductById(1L));
    }

    @Test
    void testCreateProduct_Success() {
        when(productRepository.findBySku("PROD-001")).thenReturn(Optional.empty());
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category)); // <-- Added stubbing
        when(categoryService.getCategoryById(1L)).thenReturn(categoryResponse);
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductResponse result = productService.createProduct(createProductRequest);

        assertNotNull(result);
        assertEquals("PROD-001", result.getSku());
        verify(productRepository, times(1)).save(any(Product.class));
        verify(categoryRepository, times(1)).findById(1L);
    }

    @Test
    void testCreateProduct_SKUAlreadyExists() {
        when(productRepository.findBySku("PROD-001")).thenReturn(Optional.of(product));

        assertThrows(IllegalArgumentException.class, () -> productService.createProduct(createProductRequest));
    }
}