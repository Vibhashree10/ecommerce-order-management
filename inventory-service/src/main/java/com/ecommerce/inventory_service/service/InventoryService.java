package com.ecommerce.inventory_service.service;

import com.ecommerce.inventory_service.dto.request.AdjustStockRequest;
import com.ecommerce.inventory_service.dto.request.CreateInventoryItemRequest;
import com.ecommerce.inventory_service.dto.response.AvailabilityResponse;
import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
import com.ecommerce.inventory_service.dto.response.PageResponse;
import com.ecommerce.inventory_service.dto.response.StockMovementResponse;
import org.springframework.data.domain.Pageable;

public interface InventoryService {

    InventoryItemResponse createItem(CreateInventoryItemRequest request);

    InventoryItemResponse getItem(Long productId);

    PageResponse<InventoryItemResponse> listItems(Pageable pageable);

    PageResponse<InventoryItemResponse> listLowStock(Pageable pageable);

    InventoryItemResponse adjustStock(Long productId, AdjustStockRequest request);

    AvailabilityResponse checkAvailability(Long productId, int quantity);

    PageResponse<StockMovementResponse> getMovements(Long productId, Pageable pageable);
}