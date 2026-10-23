package com.ecommerce.product_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateProductRequest {

    @NotBlank(message = "SKU cannot be blank")
    @Size(min = 3, max = 255, message = "SKU must be between 3 and 255 characters")
    private String sku;

    @NotBlank(message = "Name cannot be blank")
    @Size(min = 3, max = 255, message = "Name must be between 3 and 255 characters")
    private String name;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @NotNull(message = "Price cannot be null")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @DecimalMax(value = "999999.99", message = "Price must not exceed 999999.99")
    private BigDecimal price;

    @NotNull(message = "Quantity cannot be null")
    @Min(value = 0, message = "Quantity cannot be negative")
    private Integer quantity;

    @NotNull(message = "Category ID cannot be null")
    private Long categoryId;

    @Size(max = 255, message = "Manufacturer must not exceed 255 characters")
    private String manufacturer;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;
}
