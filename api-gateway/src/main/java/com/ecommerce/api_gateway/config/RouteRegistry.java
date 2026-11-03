package com.ecommerce.api_gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The fixed list of public paths per service (code) combined with the per-environment settings (properties).
 * Paths are forwarded UNCHANGED: the services already use the /api context path.
 */
@Component
public class RouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteRegistry.class);

    /** id, paths, enabled by default, JWT required by default */
    private record Definition(String id, List<String> paths, boolean defaultEnabled, boolean defaultAuthenticated) {
    }

    public record ActiveRoute(String id, List<String> paths, String url, String key, boolean authenticated) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("auth", List.of("/api/auth/**", "/api/users/**"), true, true),
            new Definition("product", List.of("/api/products/**", "/api/category/**", "/api/categories/**"), true, true),
            new Definition("order", List.of("/api/orders/**"), true, true),
            new Definition("notification", List.of("/api/notifications/**"), false, true),
            // Inventory is an INTERNAL service (reservations are called by order-service). Not exposed unless asked.
            new Definition("inventory", List.of("/api/inventory/**"), false, true));

    private final List<ActiveRoute> active;

    public RouteRegistry(GatewayProperties properties) {
        List<ActiveRoute> list = new ArrayList<>();
        for (Definition d : DEFINITIONS) {
            GatewayProperties.Route p = properties.getRoutes().getOrDefault(d.id(), new GatewayProperties.Route());
            boolean enabled = p.getEnabled() != null ? p.getEnabled() : d.defaultEnabled();
            String url = normalizeUrl(p.getUrl());
            if (!enabled) {
                log.info("Route '{}' is disabled", d.id());
                continue;
            }
            if (url.isEmpty()) {
                log.warn("Route '{}' has no URL (gateway.routes.{}.url / GATEWAY_ROUTES_{}_URL): not registered", d.id(), d.id(), d.id().toUpperCase());
                continue;
            }
            boolean authenticated = p.getAuthenticated() != null ? p.getAuthenticated() : d.defaultAuthenticated();
            String key = p.getKey() == null ? "" : p.getKey().trim();
            list.add(new ActiveRoute(d.id(), d.paths(), url, key, authenticated));
            log.info("Route '{}' {} -> {} (jwt required: {}, internal key: {})", d.id(), d.paths(), url, authenticated, key.isEmpty() ? "none" : "set");
        }
        this.active = List.copyOf(list);
    }

    public List<ActiveRoute> routes() {
        return active;
    }

    public Optional<ActiveRoute> byId(String id) {
        return active.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** trims, removes trailing slashes and a trailing /api (a common copy-paste from the service guides). */
    static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/api")) {
            u = u.substring(0, u.length() - 4);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

}
