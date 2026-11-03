package com.ecommerce.order_service.client;

import com.ecommerce.order_service.exception.ExternalServiceException;
import com.ecommerce.order_service.exception.ReservationNotFoundException;
import com.ecommerce.order_service.exception.ReservationStateException;
import com.ecommerce.order_service.exception.StockUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * >>> INTER-SERVICE CALL (Order -> Inventory). The Saga participant adapter: the rest of order-service only knows
 * reserve / confirm / release / get, not inventory-service's URLs, status codes or payloads.
 *
 * HTTP status -> meaning (the SAME status means different things per operation, so it is mapped per operation):
 *   reserve : 2xx ok | 409 or 404 -> StockUnavailableException (business refusal) | 401/403 and others -> ExternalServiceException
 *   confirm / release / get : 2xx ok | 404 -> ReservationNotFoundException | 409 -> ReservationStateException | others -> ExternalServiceException
 * Business refusals are listed in resilience4j ignore-exceptions, so they never open the circuit breaker; only
 * technical failures (timeouts, 5xx, wrong API key) count. Calls are idempotent on inventory's side (keyed by order number),
 * which is what makes retrying them safe.
 *
 * When clients.inventory-service.enabled=false a stub answers (reservations always succeed): for running without inventory-service only.
 * The @CircuitBreaker proxy works only when these methods are called from ANOTHER bean (OrderServiceImpl does).
 */
@Slf4j
@Component
public class InventoryClient {

    private static final String BASE = "/inventory/reservations";

    private final RestClient restClient;
    private final boolean enabled;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient inventoryRestClient,
                           @Value("${clients.inventory-service.enabled:true}") boolean enabled) {
        this.restClient = inventoryRestClient;
        this.enabled = enabled;
        if (!enabled) {
            log.warn("clients.inventory-service.enabled=false: stock reservations are STUBBED, no stock is held. Never use this on AWS.");
        }
    }

    @CircuitBreaker(name = "inventoryService")
    public ReservationInfo reserve(String orderNumber, List<ReserveItem> items) {
        if (!enabled) {
            return new ReservationInfo(orderNumber, "PENDING", null);
        }
        ReservationInfo info = execute("reserve", HttpMethod.POST, BASE, new ReserveRequest(orderNumber, items));
        if (!info.isPending() && !info.isConfirmed()) {
            // idempotent replay of an old reservation that was released / expired: do not treat it as stock held
            throw new ReservationStateException("Existing reservation for order " + orderNumber + " is " + info.status());
        }
        return info;
    }

    @CircuitBreaker(name = "inventoryService")
    public ReservationInfo confirm(String orderNumber) {
        if (!enabled) {
            return new ReservationInfo(orderNumber, "CONFIRMED", null);
        }
        return execute("confirm", HttpMethod.POST, BASE + "/{orderId}/confirm", null, orderNumber);
    }

    @CircuitBreaker(name = "inventoryService")
    public ReservationInfo release(String orderNumber) {
        if (!enabled) {
            return new ReservationInfo(orderNumber, "RELEASED", null);
        }
        return execute("release", HttpMethod.POST, BASE + "/{orderId}/release", null, orderNumber);
    }

    @CircuitBreaker(name = "inventoryService")
    public Optional<ReservationInfo> get(String orderNumber) {
        if (!enabled) {
            return Optional.of(new ReservationInfo(orderNumber, "PENDING", null));
        }
        try {
            return Optional.of(execute("get", HttpMethod.GET, BASE + "/{orderId}", null, orderNumber));
        } catch (ReservationNotFoundException e) {
            return Optional.empty();
        }
    }

    private ReservationInfo execute(String op, HttpMethod method, String uri, Object body, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(method).uri(uri, uriVariables);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status >= 200 && status < 300) {
                    ReservationInfo info = response.bodyTo(ReservationInfo.class);
                    if (info == null || info.status() == null) {
                        throw new ExternalServiceException("inventory-service returned an unreadable body for " + op);
                    }
                    return info;
                }
                String detail = errorMessage(() -> response.bodyTo(JsonNode.class), status);
                if ("reserve".equals(op) && (status == 409 || status == 404)) {
                    throw new StockUnavailableException(detail);
                }
                if (!"reserve".equals(op) && status == 404) {
                    throw new ReservationNotFoundException(detail);
                }
                if (!"reserve".equals(op) && status == 409) {
                    throw new ReservationStateException(detail);
                }
                if (status == 401 || status == 403) {
                    throw new ExternalServiceException("inventory-service rejected our API key (HTTP " + status
                            + "); check CLIENTS_INVENTORY_SERVICE_API_KEY");
                }
                throw new ExternalServiceException("inventory-service " + op + " failed with HTTP " + status + ": " + detail);
            });
        } catch (RestClientException e) {
            throw new ExternalServiceException("inventory-service unreachable: " + e.getMessage(), e);
        }
    }

    /** inventory-service errors look like {"status":409,"message":"..."}; fall back to the status code if unreadable. */
    private String errorMessage(Supplier<JsonNode> body, int status) {
        try {
            JsonNode node = body.get();
            if (node != null && node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // body empty or not JSON
        }
        return "HTTP " + status;
    }
}
