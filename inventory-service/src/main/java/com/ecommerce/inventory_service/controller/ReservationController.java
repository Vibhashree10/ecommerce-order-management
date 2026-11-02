package com.ecommerce.inventory_service.controller;

import com.ecommerce.inventory_service.dto.request.ReserveStockRequest;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;
import com.ecommerce.inventory_service.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API used by order-service (Saga participant):
 *   POST /inventory/reservations              reserve all items of an order (all-or-nothing, idempotent by orderId)
 *   POST /inventory/reservations/{id}/confirm payment succeeded -> stock committed
 *   POST /inventory/reservations/{id}/release payment failed / cancelled -> compensation
 *   GET  /inventory/reservations/{id}         current state
 * Replaying any call is safe (idempotent).
 */
@RestController
@RequestMapping("/inventory/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.reserve(request));
    }

    @PostMapping("/{orderId}/confirm")
    public ReservationResponse confirm(@PathVariable("orderId") String orderId) {
        return reservationService.confirm(orderId);
    }

    @PostMapping("/{orderId}/release")
    public ReservationResponse release(@PathVariable("orderId") String orderId) {
        return reservationService.release(orderId);
    }

    @GetMapping("/{orderId}")
    public ReservationResponse get(@PathVariable("orderId") String orderId) {
        return reservationService.get(orderId);
    }
}