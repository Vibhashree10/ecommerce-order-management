package com.ecommerce.api_gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder, RouteRegistry registry) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (RouteRegistry.ActiveRoute r : registry.routes()) {
            routes = routes.route(r.id(), spec -> spec.path(r.paths().toArray(new String[0])).uri(r.url()));
        }
        return routes.build();
    }
}
