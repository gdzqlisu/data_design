package com.gdzqlisu.datadesign.auth.audit;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditServiceTest extends IntegrationTestBase {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository logs;

    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }

    @Test
    void recordsEventWithAllFields() {
        long userId = randomUserId();

        audit.record(userId, AuditEvent.LOGIN_PENDING, "GITHUB", "203.0.113.7", "JUnit-Agent",
                Map.of("login", "octocat"));

        AuditLog saved = logs.findAllByUserIdOrderByCreatedAtDesc(userId).get(0);
        assertThat(saved.getEvent()).isEqualTo(AuditEvent.LOGIN_PENDING);
        assertThat(saved.getProvider()).isEqualTo("GITHUB");
        assertThat(saved.getIp()).isEqualTo("203.0.113.7");
        assertThat(saved.getDetailJson()).contains("octocat");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void nullDetailIsPersistedAsNull() {
        long userId = randomUserId();

        audit.record(userId, AuditEvent.LOGOUT, null, null, null, null);

        AuditLog saved = logs.findAllByUserIdOrderByCreatedAtDesc(userId).get(0);
        assertThat(saved.getDetailJson()).isNull();
        assertThat(saved.getEvent()).isEqualTo(AuditEvent.LOGOUT);
    }

    @Test
    void readsClientIpFromForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("10.0.0.1");

        assertThat(AuditService.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        assertThat(AuditService.clientIp(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void truncatesOverlongUserAgent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "x".repeat(900));

        assertThat(AuditService.userAgent(request)).hasSize(512);
    }
}
