package com.ecommerce.api_gateway.filter;

import com.ecommerce.api_gateway.config.RouteRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Runs for every proxied request:
 *  1. REMOVES headers a client could use to impersonate the gateway or a user (X-Internal-Api-Key, X-User-Id, X-User-Email).
 *  2. Adds the internal API key of the target service (so clients never need to know it).
 *  3. Adds X-User-Id / X-User-Email from the validated JWT (subject / email claim).
 *  4. Adds X-Request-Id for tracing across services.
 * The Authorization header is forwarded unchanged (product-service validates the same JWT itself).
 */
@Component
public class ForwardHeadersFilter implements GlobalFilter, Ordered {

    public static final String INTERNAL_KEY = "X-Internal-Api-Key";
    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String REQUEST_ID = "X-Request-Id";

    private final RouteRegistry registry;

    public ForwardHeadersFilter(RouteRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String key = route == null ? "" : registry.byId(route.getId()).map(RouteRegistry.ActiveRoute::key).orElse("");

        return ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> Mono.justOrEmpty(ctx.getAuthentication()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(auth -> {
                    ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> {
                        h.remove(INTERNAL_KEY);
                        h.remove(USER_ID);
                        h.remove(USER_EMAIL);
                        h.set(REQUEST_ID, UUID.randomUUID().toString());
                        if (!key.isEmpty()) {
                            h.set(INTERNAL_KEY, key);
                        }
                        auth.filter(JwtAuthenticationToken.class::isInstance)
                                .map(a -> ((JwtAuthenticationToken) a).getToken())
                                .ifPresent(jwt -> {
                                    if (jwt.getSubject() != null) {
                                        h.set(USER_ID, jwt.getSubject());
                                    }
                                    String email = email(jwt);
                                    if (email != null) {
                                        h.set(USER_EMAIL, email);
                                    }
                                });
                    }).build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    private static String email(Jwt jwt) {
        String claim = jwt.getClaimAsString("email");
        if (claim != null && !claim.isBlank()) {
            return claim;
        }
        String sub = jwt.getSubject();
        return sub != null && sub.contains("@") ? sub : null;
    }


    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
