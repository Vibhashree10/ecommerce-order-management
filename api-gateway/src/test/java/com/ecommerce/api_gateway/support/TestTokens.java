package com.ecommerce.api_gateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

/** Creates signed JWTs the way auth-service (jjwt) would, for the gateway tests. */
public final class TestTokens {

    /** Base64 text of 64 bytes, like a secret made with: openssl rand -base64 64 */
    public static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private TestTokens() {
    }

    public static String sign(byte[] key, JWSAlgorithm alg, String subject, long expiresInSeconds) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("email", subject)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(alg), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Key = the secret's raw text bytes (HS512). */
    public static String rawKeyToken(String subject) {
        return sign(SECRET.getBytes(StandardCharsets.UTF_8), JWSAlgorithm.HS512, subject, 300);
    }

    /** Key = the Base64-decoded secret (jjwt's usual behaviour for Base64 secrets). */
    public static String decodedKeyToken(String subject, JWSAlgorithm alg, long expiresInSeconds) {
        return sign(Base64.getDecoder().decode(SECRET), alg, subject, expiresInSeconds);
    }
}
