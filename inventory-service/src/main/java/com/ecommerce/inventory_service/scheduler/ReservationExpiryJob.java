package com.ecommerce.inventory_service.scheduler;

import com.ecommerce.inventory_service.entity.ReservationStatus;
import com.ecommerce.inventory_service.repository.StockReservationRepository;
import com.ecommerce.inventory_service.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Soft-lock expiry: releases stock held by reservations that were never confirmed.
 * Safe with several service instances: ReservationService.expire() is a compare-and-set,
 * so only one instance can release a given order. (Phase 2: ShedLock to avoid duplicate scans.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "inventory.reservation.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final int BATCH_SIZE = 50;

    private final StockReservationRepository reservationRepository;
    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-scan-ms:60000}")
    public void releaseExpiredReservations() {
        List<String> orderIds = reservationRepository.findOrderIdsWithExpired(
                ReservationStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));
        for (String orderId : orderIds) {
            try {
                reservationService.expire(orderId);
            } catch (Exception e) {
                log.error("Could not expire reservation for order {}: {}", orderId, e.toString());
            }
        }
        if (!orderIds.isEmpty()) {
            log.info("Expiry sweep processed {} order(s)", orderIds.size());
        }
    }
}