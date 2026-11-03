package com.ecommerce.common_lib.error;

import com.ecommerce.common_lib.http.Headers;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    @Test
    void holdsFields() {
        LocalDateTime now = LocalDateTime.now();
        ErrorResponse e = new ErrorResponse(now, 404, "Not Found", "missing", "/api/x");
        assertThat(e.status()).isEqualTo(404);
        assertThat(e.timestamp()).isEqualTo(now);
        assertThat(e.path()).isEqualTo("/api/x");
    }

    @Test
    void headerNamesAreTheOnesTheServicesUse() {
        assertThat(Headers.INTERNAL_API_KEY).isEqualTo("X-Internal-Api-Key");
        assertThat(Headers.IDEMPOTENCY_KEY).isEqualTo("Idempotency-Key");
    }
}
