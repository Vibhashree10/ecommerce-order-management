package com.ecommerce.inventory_service.dto.response;

import com.ecommerce.inventory_service.entity.ReservationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ReservationResponse(
        String orderId,
        ReservationStatus status,
        LocalDateTime expiresAt,
        List<ReservationItemResponse> items) {
}