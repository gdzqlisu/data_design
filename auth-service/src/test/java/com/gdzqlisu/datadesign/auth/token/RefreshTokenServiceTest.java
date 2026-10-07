package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RefreshTokenServiceTest extends IntegrationTestBase {

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper mapper;

    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
    }

    @Test
    void issuedTokenCanBeRotatedOnce() {
        long userId = randomUserId();
        IssuedRefreshToken first = service.issue(userId, 0, "JUnit");

        IssuedRefreshToken second = service.rotate(first.rawToken(), "JUnit");

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(second.jti()).isNotEqualTo(first.jti());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeChain() {
        long userId = randomUserId();
        IssuedRefreshToken first = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken second = service.rotate(first.rawToken(), "JUnit");

        assertThatThrownBy(() -> service.rotate(first.rawToken(), "JUnit"))
                .isInstanceOf(RefreshTokenReuseException.class);

        assertThatThrownBy(() -> service.rotate(second.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokeAllForUserInvalidatesEveryTokenOfThatUser() {
        long userId = randomUserId();
        IssuedRefreshToken a = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken b = service.issue(userId, 0, "JUnit");

        service.revokeAllForUser(userId);

        assertThatThrownBy(() -> service.rotate(a.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotate(b.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("11111111-1111-1111-1111-111111111111.deadbeef", "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("not-a-token", "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        RefreshTokenService shortLived = new RefreshTokenService(redis, mapper, Duration.ofMillis(80));
        long userId = randomUserId();
        IssuedRefreshToken token = shortLived.issue(userId, 0, "JUnit");

        Thread.sleep(200);

        assertThatThrownBy(() -> shortLived.rotate(token.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokeRemovesOnlyTheGivenToken() {
        long userId = randomUserId();
        IssuedRefreshToken a = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken b = service.issue(userId, 0, "JUnit");

        service.revoke(a.rawToken());

        assertThatThrownBy(() -> service.rotate(a.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(service.rotate(b.rawToken(), "JUnit").rawToken()).isNotBlank();
    }
}
