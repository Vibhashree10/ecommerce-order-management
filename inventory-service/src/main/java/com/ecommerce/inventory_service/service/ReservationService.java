package com.ecommerce.inventory_service.service;

import com.ecommerce.inventory_service.dto.request.ReserveStockRequest;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;

/**
 * Participant in the order Saga. order-service calls reserve first, then confirm (success path)
 * or release (compensation when payment fails or the order is cancelled).
 */
public interface ReservationService {

    ReservationResponse reserve(ReserveStockRequest request);

    ReservationResponse confirm(String orderId);

    ReservationResponse release(String orderId);

    ReservationResponse get(String orderId);

    /** Called by the expiry job: releases the order's stock if still PENDING. No-op otherwise. */
    void expire(String orderId);
}