package com.ecommerce.inventory_service.controller;

import com.ecommerce.inventory_service.dto.request.AdjustStockRequest;
import com.ecommerce.inventory_service.dto.request.CreateInventoryItemRequest;
import com.ecommerce.inventory_service.dto.response.AvailabilityResponse;
import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
import com.ecommerce.inventory_service.dto.response.PageResponse;
import com.ecommerce.inventory_service.dto.response.StockMovementResponse;
import com.ecommerce.inventory_service.service.InventoryService;
import com.ecommerce.inventory_service.util.Constants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/inventory/items")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<InventoryItemResponse> createItem(@Valid @RequestBody CreateInventoryItemRequest request) {
        InventoryItemResponse created = inventoryService.createItem(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{productId}").buildAndExpand(created.productId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<InventoryItemResponse> listItems(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return inventoryService.listItems(pageable(page, size, Sort.by("productId")));
    }

    @GetMapping("/low-stock")
    public PageResponse<InventoryItemResponse> listLowStock(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return inventoryService.listLowStock(pageable(page, size, Sort.by("productId")));
    }

    @GetMapping("/{productId}")
    public InventoryItemResponse getItem(@PathVariable("productId") Long productId) {
        return inventoryService.getItem(productId);
    }

    @GetMapping("/{productId}/availability")
    public AvailabilityResponse checkAvailability(@PathVariable("productId") Long productId,
                                                  @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        return inventoryService.checkAvailability(productId, quantity);
    }

    @PutMapping("/{productId}/stock")
    public InventoryItemResponse adjustStock(@PathVariable("productId") Long productId,
                                             @Valid @RequestBody AdjustStockRequest request) {
        return inventoryService.adjustStock(productId, request);
    }

    @GetMapping("/{productId}/movements")
    public PageResponse<StockMovementResponse> getMovements(
            @PathVariable("productId") Long productId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return inventoryService.getMovements(productId, pageable(page, size, Sort.unsorted()));
    }

    private Pageable pageable(int page, int size, Sort sort) {
        int safeSize = Math.min(Math.max(size, 1), Constants.MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, sort);
    }
}