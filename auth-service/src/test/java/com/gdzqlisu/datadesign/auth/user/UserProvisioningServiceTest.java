package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditLogRepository;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserProvisioningServiceTest extends IntegrationTestBase {

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Autowired
    private AuditLogRepository auditLogs;

    private static final MockHttpServletRequest REQUEST = new MockHttpServletRequest();

    private GitHubProfile profile(String id, String login, String email) {
        return new GitHubProfile(id, login, "The " + login, email, "https://avatars.example/" + login + ".png");
    }

    private String uniqueId() {
        return Long.toString(System.nanoTime());
    }

    @Test
    void firstLoginCreatesPendingUserWithGithubIdentity() {
        String githubId = uniqueId();

        LoginOutcome outcome = provisioning.login(profile(githubId, "octocat", "octocat@github.com"), REQUEST);

        assertThat(outcome.newlyCreated()).isTrue();
        assertThat(outcome.user().getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(outcome.user().getRole()).isEqualTo(Role.MEMBER);
        assertThat(identities.findByProviderAndProviderUserId(AuthProvider.GITHUB, githubId)).isPresent();
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(outcome.user().getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_PENDING);
    }

    @Test
    void secondLoginReusesTheSameUserAndDoesNotDuplicateIdentity() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat-renamed", null), REQUEST);

        assertThat(second.newlyCreated()).isFalse();
        assertThat(second.user().getId()).isEqualTo(first.user().getId());
        assertThat(identities.findAllByUserIdOrderByCreatedAtAsc(first.user().getId())).hasSize(1);
    }

    @Test
    void approvedUserLoginIsAuditedAsSuccess() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);
        User user = users.findById(first.user().getId()).orElseThrow();
        user.approve(Role.MEMBER, null);
        users.saveAndFlush(user);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        assertThat(second.user().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_SUCCESS);
    }

    @Test
    void disabledUserLoginIsAuditedAsDisabled() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);
        User user = users.findById(first.user().getId()).orElseThrow();
        user.approve(Role.MEMBER, null);
        user.disable();
        users.saveAndFlush(user);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        assertThat(second.user().getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_DISABLED);
    }

    @Test
    void sameEmailFromDifferentGithubAccountsIsNotMerged() {
        String email = "shared-" + System.nanoTime() + "@example.com";
        LoginOutcome first = provisioning.login(profile(uniqueId(), "alice", email), REQUEST);
        LoginOutcome second = provisioning.login(profile(uniqueId(), "bob", email), REQUEST);

        assertThat(second.user().getId()).isNotEqualTo(first.user().getId());
    }
}
