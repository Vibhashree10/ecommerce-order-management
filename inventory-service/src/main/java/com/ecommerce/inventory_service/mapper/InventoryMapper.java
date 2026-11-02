package com.ecommerce.inventory_service.mapper;

import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
import com.ecommerce.inventory_service.dto.response.ReservationItemResponse;
import com.ecommerce.inventory_service.dto.response.ReservationResponse;
import com.ecommerce.inventory_service.dto.response.StockMovementResponse;
import com.ecommerce.inventory_service.entity.InventoryItem;
import com.ecommerce.inventory_service.entity.StockMovement;
import com.ecommerce.inventory_service.entity.StockReservation;
import org.springframework.stereotype.Component;

import java.util.List;

/** Hand-written mapper (no MapStruct): fewer moving parts in the Lombok annotation-processor chain. */
@Component
public class InventoryMapper {

    public InventoryItemResponse toResponse(InventoryItem item) {
        return new InventoryItemResponse(
                item.getProductId(),
                item.getAvailableQuantity(),
                item.getReservedQuantity(),
                item.getReorderLevel(),
                item.getAvailableQuantity() <= item.getReorderLevel(),
                item.getUpdatedAt());
    }

    public StockMovementResponse toMovementResponse(StockMovement movement) {
        return new StockMovementResponse(
                movement.getProductId(),
                movement.getMovementType(),
                movement.getQuantityDelta(),
                movement.getReference(),
                movement.getReason(),
                movement.getCreatedAt());
    }

    /** All rows of one order always share the same status, so the first row represents the order. */
    public ReservationResponse toReservationResponse(String orderId, List<StockReservation> rows) {
        StockReservation first = rows.get(0);
        List<ReservationItemResponse> items = rows.stream()
                .map(r -> new ReservationItemResponse(r.getProductId(), r.getQuantity()))
                .toList();
        return new ReservationResponse(orderId, first.getStatus(), first.getExpiresAt(), items);
    }
}