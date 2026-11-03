package com.ecommerce.order_service.dto.request;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 255) String reason) {
}
