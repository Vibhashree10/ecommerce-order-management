package com.ecommerce.common_lib.error;

import java.time.LocalDateTime;

/** The error body every service returns. */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path) {
}
