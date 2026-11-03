package com.ecommerce.api_gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.ecommerce.api_gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderFactoryTest {

    private final ReactiveJwtDecoder decoder = JwtDecoderFactory.create(TestTokens.SECRET);

    @Test
    void accepts_rawKeyHS512() {
        assertThat(decoder.decode(TestTokens.rawKeyToken("a@x.com")).block().getSubject()).isEqualTo("a@x.com");
    }

    @Test
    void accepts_base64DecodedKey_allHsAlgorithms() {
        for (JWSAlgorithm alg : new JWSAlgorithm[]{JWSAlgorithm.HS512, JWSAlgorithm.HS384, JWSAlgorithm.HS256}) {
            assertThat(decoder.decode(TestTokens.decodedKeyToken("b@x.com", alg, 300)).block().getSubject())
                    .as(alg.getName()).isEqualTo("b@x.com");
        }
    }

    @Test
    void rejects_wrongSecret() {
        String token = TestTokens.sign("a-completely-different-secret-of-some-length-1234567890-abcdefghijkl".getBytes(StandardCharsets.UTF_8),
                JWSAlgorithm.HS512, "evil", 300);
        assertThatThrownBy(() -> decoder.decode(token).block()).isInstanceOf(JwtException.class);
    }

    @Test
    void rejects_expired_andGarbage() {
        String expired = TestTokens.decodedKeyToken("a", JWSAlgorithm.HS512, -3600);
        assertThatThrownBy(() -> decoder.decode(expired).block()).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not.a.jwt").block()).isInstanceOf(JwtException.class);
    }

    @Test
    void blankSecret_failsFast() {
        assertThatThrownBy(() -> JwtDecoderFactory.create("  ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
    }
}
