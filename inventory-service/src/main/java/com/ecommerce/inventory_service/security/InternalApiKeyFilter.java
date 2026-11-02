package com.ecommerce.inventory_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Phase-1 service-to-service protection: callers (order-service, scripts) send the shared secret
 * in header X-Internal-Api-Key. If no key is configured the filter lets everything through (local dev).
 *
 * Deliberately NOT a @Component: a Filter bean would also be auto-registered in the servlet
 * container and run twice. SecurityConfig creates it with "new" and adds it to the security chain only.
 * Phase 2: replace with JWT validation against auth-service.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private final byte[] expectedKey;

    public InternalApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey == null ? new byte[0] : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedKey.length == 0 || keyMatches(request.getHeader(HEADER))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        }
        chain.doFilter(request, response);
    }

    private boolean keyMatches(String provided) {
        if (provided == null) {
            return false;
        }
        // constant-time comparison: does not leak how many characters matched
        return MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8));
    }
}