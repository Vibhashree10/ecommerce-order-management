package com.ecommerce.inventory_service.support;

import com.ecommerce.inventory_service.security.InternalApiKeyFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security setup for the @WebMvcTest controller slices: every request needs the API key.
 * (The production SecurityConfig also exposes actuator health, which needs actuator beans that a
 * controller slice does not load. The real configuration is covered by InventoryApiIntegrationTest.)
 */
@TestConfiguration
public class TestSecurityConfig {

    public static final String API_KEY = "test-key";
    public static final String API_KEY_HEADER = InternalApiKeyFilter.HEADER;

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new InternalApiKeyFilter(API_KEY), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}