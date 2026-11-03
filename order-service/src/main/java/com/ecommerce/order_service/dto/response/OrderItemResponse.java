package com.ecommerce.order_service.dto.response;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String sku,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {
}
