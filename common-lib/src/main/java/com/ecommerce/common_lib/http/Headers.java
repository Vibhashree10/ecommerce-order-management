package com.ecommerce.common_lib.http;

/** HTTP header names used between the gateway and the services. */
public final class Headers {

    public static final String INTERNAL_API_KEY = "X-Internal-Api-Key";
    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private Headers() {
    }
}
