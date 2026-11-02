package com.ecommerce.inventory_service.controller;

import com.ecommerce.inventory_service.dto.request.AdjustStockRequest;
import com.ecommerce.inventory_service.dto.request.CreateInventoryItemRequest;
import com.ecommerce.inventory_service.dto.response.AvailabilityResponse;
import com.ecommerce.inventory_service.dto.response.InventoryItemResponse;
import com.ecommerce.inventory_service.dto.response.PageResponse;
import com.ecommerce.inventory_service.exception.DuplicateResourceException;
import com.ecommerce.inventory_service.exception.ExternalServiceException;
import com.ecommerce.inventory_service.exception.InsufficientStockException;
import com.ecommerce.inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.inventory_service.service.InventoryService;
import com.ecommerce.inventory_service.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static com.ecommerce.inventory_service.support.TestSecurityConfig.API_KEY;
import static com.ecommerce.inventory_service.support.TestSecurityConfig.API_KEY_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only: routing, validation, status codes, JSON shape, error mapping, API-key protection. */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private InventoryService inventoryService;

    private InventoryItemResponse item(long productId, int available, int reserved, int reorder) {
        return new InventoryItemResponse(productId, available, reserved, reorder, available <= reorder,
                LocalDateTime.of(2030, 1, 1, 10, 0));
    }

    // ------------------------------------------------------------------ create
    @Test
    void createItem_returns201WithLocationAndBody() throws Exception {
        when(inventoryService.createItem(any(CreateInventoryItemRequest.class))).thenReturn(item(10, 25, 0, 5));

        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":10,\"quantity\":25,\"reorderLevel\":5}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/inventory/items/10")))
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.availableQuantity").value(25))
                .andExpect(jsonPath("$.lowStock").value(false));
    }

    @Test
    void createItem_returns400ListingEveryInvalidField() throws Exception {
        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("productId")))
                .andExpect(jsonPath("$.message", containsString("quantity")));

        verifyNoInteractions(inventoryService);
    }

    @Test
    void createItem_returns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createItem_returns409WhenInventoryAlreadyExists() throws Exception {
        when(inventoryService.createItem(any(CreateInventoryItemRequest.class)))
                .thenThrow(new DuplicateResourceException("Inventory already exists for product 10"));

        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":10,\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    void createItem_returns404WhenProductServiceSaysProductIsUnknown() throws Exception {
        when(inventoryService.createItem(any(CreateInventoryItemRequest.class)))
                .thenThrow(new ResourceNotFoundException("Product 99 does not exist in product-service"));

        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":99,\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createItem_returns503WhenProductServiceIsDown() throws Exception {
        when(inventoryService.createItem(any(CreateInventoryItemRequest.class)))
                .thenThrow(new ExternalServiceException("product-service unreachable"));

        mockMvc.perform(post("/inventory/items").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":10,\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable());
    }

    // ------------------------------------------------------------------ read
    @Test
    void getItem_returns200WithJson() throws Exception {
        when(inventoryService.getItem(10L)).thenReturn(item(10, 6, 4, 3));

        mockMvc.perform(get("/inventory/items/10").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.availableQuantity").value(6))
                .andExpect(jsonPath("$.reservedQuantity").value(4));
    }

    @Test
    void getItem_returns404WhenNoInventoryRecord() throws Exception {
        when(inventoryService.getItem(77L)).thenThrow(new ResourceNotFoundException("No inventory record for product 77"));

        mockMvc.perform(get("/inventory/items/77").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message", containsString("product 77")));
    }

    @Test
    void getItem_returns400WhenProductIdIsNotANumber() throws Exception {
        mockMvc.perform(get("/inventory/items/abc").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    @Test
    void unknownRoute_returns404_notAServerError() throws Exception {
        mockMvc.perform(get("/inventory/does-not-exist").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void listItems_capsThePageSizeAt100() throws Exception {
        when(inventoryService.listItems(any(Pageable.class))).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        mockMvc.perform(get("/inventory/items?page=-3&size=1000").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(inventoryService).listItems(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void lowStock_returnsPagedItems() throws Exception {
        when(inventoryService.listLowStock(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(item(5, 1, 0, 3)), 0, 20, 1, 1));

        mockMvc.perform(get("/inventory/items/low-stock").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(5))
                .andExpect(jsonPath("$.content[0].lowStock").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------ availability
    @Test
    void availability_returnsWhetherQuantityIsCovered() throws Exception {
        when(inventoryService.checkAvailability(10L, 4)).thenReturn(new AvailabilityResponse(10L, 4, 3, false));

        mockMvc.perform(get("/inventory/items/10/availability?quantity=4").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(false))
                .andExpect(jsonPath("$.availableQuantity").value(3));
    }

    @Test
    void availability_returns400WhenQuantityIsBelowOne() throws Exception {
        mockMvc.perform(get("/inventory/items/10/availability?quantity=0").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    // ------------------------------------------------------------------ adjust
    @Test
    void adjustStock_returns200WithUpdatedItem() throws Exception {
        when(inventoryService.adjustStock(eq(10L), any(AdjustStockRequest.class))).thenReturn(item(10, 15, 0, 3));

        mockMvc.perform(put("/inventory/items/10/stock").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"goods received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(15));
    }

    @Test
    void adjustStock_returns409WhenStockWouldGoNegative() throws Exception {
        when(inventoryService.adjustStock(eq(10L), any(AdjustStockRequest.class)))
                .thenThrow(new InsufficientStockException(10L, 5, 3));

        mockMvc.perform(put("/inventory/items/10/stock").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-5,\"reason\":\"damaged\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("requested 5, available 3")));
    }

    @Test
    void adjustStock_returns400WhenReasonIsBlank() throws Exception {
        mockMvc.perform(put("/inventory/items/10/stock").header(API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("reason")));

        verifyNoInteractions(inventoryService);
    }

    // ------------------------------------------------------------------ api key
    @Test
    void anyEndpoint_returns401WithoutApiKey() throws Exception {
        mockMvc.perform(get("/inventory/items/10")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inventoryService);
    }

    @Test
    void anyEndpoint_returns401WithWrongApiKey() throws Exception {
        mockMvc.perform(get("/inventory/items/10").header(API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inventoryService);
    }
}