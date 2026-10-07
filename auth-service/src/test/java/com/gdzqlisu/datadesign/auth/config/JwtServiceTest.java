package com.gdzqlisu.datadesign.auth.config;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789-abcdef";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15), "data-design");

    @Test
    void issuesTokenCarryingUserIdRoleAndTokenVersion() {
        JwtService service = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        AccessTokenClaims claims = service.parse(service.issue(7L, "ADMIN", 3));

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo("ADMIN");
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void rejectsExpiredToken() {
        JwtService issuing = new JwtService(properties, Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String issuedAnHourAgo = issuing.issue(7L, "MEMBER", 0);
        JwtService now = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> now.parse(issuedAnHourAgo)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtService issuer = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        JwtService verifier = new JwtService(
                new JwtProperties("another-secret-0123456789-0123456789-abcdef", Duration.ofMinutes(15), "data-design"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.parse(issuer.issue(7L, "MEMBER", 0))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(15), "data-design"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }
}
