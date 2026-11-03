package com.ecommerce.api_gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteRegistryTest {

    private GatewayProperties props() {
        return new GatewayProperties();
    }

    private void route(GatewayProperties p, String id, String url, String key, Boolean enabled, Boolean authenticated) {
        GatewayProperties.Route r = new GatewayProperties.Route();
        r.setUrl(url);
        r.setKey(key);
        r.setEnabled(enabled);
        r.setAuthenticated(authenticated);
        p.getRoutes().put(id, r);
    }

    @Test
    void normalizeUrl_removesTrailingSlashAndApi() {
        assertThat(RouteRegistry.normalizeUrl("http://h:8084/api/")).isEqualTo("http://h:8084");
        assertThat(RouteRegistry.normalizeUrl(" http://h/api ")).isEqualTo("http://h");
        assertThat(RouteRegistry.normalizeUrl("http://h:1")).isEqualTo("http://h:1");
        assertThat(RouteRegistry.normalizeUrl(null)).isEmpty();
    }

    @Test
    void defaults_authProductOrderOn_notificationInventoryOff() {
        GatewayProperties p = props();
        for (String id : new String[]{"auth", "product", "order", "notification", "inventory"}) {
            route(p, id, "http://" + id + ":80/api", "", null, null);
        }
        RouteRegistry registry = new RouteRegistry(p);

        assertThat(registry.routes()).extracting(RouteRegistry.ActiveRoute::id).containsExactly("auth", "product", "order");
        assertThat(registry.byId("order").orElseThrow().url()).isEqualTo("http://order:80");
        assertThat(registry.byId("order").orElseThrow().authenticated()).isTrue();
    }

    @Test
    void explicitSettings_enableInventory_makeProductPublic_andKeepKey() {
        GatewayProperties p = props();
        route(p, "inventory", "http://inv", "k1", true, null);
        route(p, "product", "http://prod", null, null, false);
        RouteRegistry registry = new RouteRegistry(p);

        assertThat(registry.byId("inventory").orElseThrow().key()).isEqualTo("k1");
        assertThat(registry.byId("product").orElseThrow().authenticated()).isFalse();
    }

    @Test
    void routeWithoutUrl_isNotRegistered() {
        GatewayProperties p = props();
        route(p, "order", "  ", "k", null, null);
        assertThat(new RouteRegistry(p).routes()).isEmpty();
    }
}
