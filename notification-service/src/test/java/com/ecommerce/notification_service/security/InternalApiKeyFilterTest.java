package com.ecommerce.notification_service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyFilterTest {

    @BeforeEach
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the filter and returns the authentication it left in the security context (null = none). */
    private Authentication run(InternalApiKeyFilter filter, String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, headerValue);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the request must always continue down the chain").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void correctKey_authenticatesAsInternalService() throws Exception {
        Authentication auth = run(new InternalApiKeyFilter("secret-123"), "secret-123");

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("internal-service");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongKey_doesNotAuthenticate() throws Exception {
        assertThat(run(new InternalApiKeyFilter("secret-123"), "secret-124")).isNull();
    }

    @Test
    void keyWithDifferentLength_doesNotAuthenticate() throws Exception {
        assertThat(run(new InternalApiKeyFilter("secret-123"), "secret")).isNull();
    }

    @Test
    void missingHeader_doesNotAuthenticate() throws Exception {
        assertThat(run(new InternalApiKeyFilter("secret-123"), null)).isNull();
    }

    @Test
    void noConfiguredKey_leavesTheApiOpen_forLocalDevelopment() throws Exception {
        assertThat(run(new InternalApiKeyFilter(""), null)).isNotNull();
    }

    @Test
    void nullConfiguredKey_isTreatedAsOpen() throws Exception {
        assertThat(run(new InternalApiKeyFilter(null), "anything")).isNotNull();
    }
}
