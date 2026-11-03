package com.ecommerce.api_gateway.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * auth-service signs with jjwt, and how it turns the configured secret into a key (raw text bytes or Base64-decoded)
 * and which HS algorithm it picks (by key size) is not visible from the outside. So the gateway accepts a token if ANY
 * of the combinations {raw bytes, Base64-decoded bytes} x {HS512, HS384, HS256} verifies it. Only the one shared secret
 * is trusted; a token signed with anything else is rejected, as are expired tokens.
 */
public final class JwtDecoderFactory {

    private JwtDecoderFactory() {
    }

    public static ReactiveJwtDecoder create(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is empty: set JWT_SECRET (the same value auth-service signs tokens with)");
        }
        String trimmed = secret.trim();
        List<byte[]> keys = new ArrayList<>();
        keys.add(trimmed.getBytes(StandardCharsets.UTF_8));
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length > 0 && !Arrays.equals(decoded, keys.get(0))) {
                keys.add(decoded);
            }
        } catch (IllegalArgumentException notBase64) {
            // the secret is plain text: only the raw-bytes key applies
        }

        List<ReactiveJwtDecoder> decoders = new ArrayList<>();
        for (byte[] key : keys) {
            for (MacAlgorithm alg : new MacAlgorithm[]{MacAlgorithm.HS512, MacAlgorithm.HS384, MacAlgorithm.HS256}) {
                decoders.add(NimbusReactiveJwtDecoder
                        .withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                        .macAlgorithm(alg)
                        .build());
            }
        }
        return token -> Flux.fromIterable(decoders)
                .concatMap(d -> d.decode(token).onErrorResume(JwtException.class, e -> Mono.empty()))
                .next()
                .switchIfEmpty(Mono.<Jwt>error(new BadJwtException("Invalid or expired token")));
    }

}
