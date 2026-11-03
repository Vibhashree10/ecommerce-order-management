package com.ecommerce.api_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * gateway.routes.&lt;id&gt;.{url,key,enabled,authenticated}   ids: auth, product, order, notification, inventory
 * gateway.jwt.secret   the SAME secret auth-service signs tokens with (env JWT_SECRET)
 * Environment variables: GATEWAY_ROUTES_ORDER_URL, GATEWAY_ROUTES_ORDER_KEY, GATEWAY_ROUTES_ORDER_ENABLED ...
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Jwt jwt = new Jwt();

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public static class Route {
        /** Base URL of the service: http://host:port (a trailing /api or / is removed; paths are forwarded unchanged). */
        private String url;
        /** Internal API key the gateway sends to that service (X-Internal-Api-Key). Empty = send none. */
        private String key;
        /** null = use the route's built-in default. */
        private Boolean enabled;
        /** null = use the route's built-in default (true = a valid JWT is required). */
        private Boolean authenticated;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Boolean getAuthenticated() { return authenticated; }
        public void setAuthenticated(Boolean authenticated) { this.authenticated = authenticated; }
    }

    public static class Jwt {
        private String secret = "";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}
