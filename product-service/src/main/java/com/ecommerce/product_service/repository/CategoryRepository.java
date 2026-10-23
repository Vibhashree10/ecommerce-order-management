package com.ecommerce.product_service.repository;

import com.ecommerce.product_service.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByName(String name);

    @Query("SELECT c FROM Category c WHERE c.active = true")
    Page<Category> findAllActiveCategories(Pageable pageable);

    @Query("SELECT c FROM Category c WHERE c.active = true")
    List<Category> findAllActive();

    Page<Category> findByNameContainingIgnoreCase(String name, Pageable pageable);

    long countByActive(Boolean active);
}
