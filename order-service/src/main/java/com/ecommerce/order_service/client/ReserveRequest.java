package com.ecommerce.order_service.client;

import java.util.List;

/** Body of POST /inventory/reservations. orderId is our order number: it is also inventory's idempotency key. */
public record ReserveRequest(String orderId, List<ReserveItem> items) {
}
