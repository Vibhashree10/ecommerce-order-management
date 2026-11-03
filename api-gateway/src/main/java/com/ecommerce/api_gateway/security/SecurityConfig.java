package com.ecommerce.api_gateway.security;

import com.ecommerce.api_gateway.config.GatewayProperties;
import com.ecommerce.api_gateway.config.RouteRegistry;
import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Reachable without a token: creating an account and logging in cannot require a token. */
    static final String[] PUBLIC_POST = {"/api/auth/register", "/api/auth/login"};

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) {
        return JwtDecoderFactory.create(properties.getJwt().getSecret());
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveJwtDecoder decoder,
                                                         RouteRegistry registry) {
        List<String> openPaths = new ArrayList<>();
        registry.routes().stream().filter(r -> !r.authenticated()).forEach(r -> openPaths.addAll(r.paths()));
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint();
        ServerAccessDeniedHandler denied = (exchange, e) ->
                JsonAuthenticationEntryPoint.write(exchange, HttpStatus.FORBIDDEN, "Forbidden", "Access denied");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .authorizeExchange(auth -> {
                    auth.matchers(EndpointRequest.to("health")).permitAll();
                    auth.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.pathMatchers(HttpMethod.POST, PUBLIC_POST).permitAll();
                    if (!openPaths.isEmpty()) {
                        auth.pathMatchers(openPaths.toArray(new String[0])).permitAll();
                    }
                    auth.anyExchange().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(entryPoint)
                        .jwt(j -> j.jwtDecoder(decoder)))
                .build();
    }

}
