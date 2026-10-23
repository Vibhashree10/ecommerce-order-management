package com.ecommerce.product_service.service;

import com.ecommerce.product_service.dto.request.UpdateProductRequest;
import com.ecommerce.product_service.dto.response.CategoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface CategoryService {

    CategoryResponse createCategory(String name, String description, String imageUrl);

    CategoryResponse getCategoryById(Long id);

    Page<CategoryResponse> getAllCategories(Pageable pageable);

    List<CategoryResponse> getAllActiveCategories();

    Page<CategoryResponse> searchByName(String name, Pageable pageable);

    CategoryResponse updateCategory(Long id, String name, String description, String imageUrl);

    void deleteCategory(Long id);

    void deactivateCategory(Long id);

    void activateCategory(Long id);

    boolean categoryExists(Long id);

    long getActiveCategories();
}
