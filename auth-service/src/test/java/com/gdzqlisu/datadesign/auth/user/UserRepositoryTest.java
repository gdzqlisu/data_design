package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Test
    void newUserStartsPendingWithMemberRole() {
        User saved = users.saveAndFlush(User.newPending("octocat", "octocat@example.com", null));

        List<User> pending = users.findAllByStatusOrderByCreatedAtAsc(UserStatus.PENDING);

        assertThat(pending).extracting(User::getId).contains(saved.getId());
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        assertThat(saved.getTokenVersion()).isZero();
        assertThat(saved.isBreakGlass()).isFalse();
    }

    @Test
    void approveSetsRoleStatusAndAuditFields() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));

        user.approve(Role.STRATEGIST, 99L);
        users.saveAndFlush(user);

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.getRole()).isEqualTo(Role.STRATEGIST);
        assertThat(reloaded.getApprovedBy()).isEqualTo(99L);
        assertThat(reloaded.getApprovedAt()).isNotNull();
    }

    @Test
    void findsUserByProviderIdentity() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));
        identities.saveAndFlush(UserIdentity.github(user, "583231", "octocat", null, null));

        Optional<User> found = users.findByProviderAndProviderUserId(AuthProvider.GITHUB, "583231");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
    }

    @Test
    void disableAndChangeRoleBumpTokenVersion() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));
        int before = user.getTokenVersion();

        user.changeRole(Role.VIEWER);
        users.saveAndFlush(user);

        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(before + 1);

        User reloaded = users.findById(user.getId()).orElseThrow();
        reloaded.disable();
        users.saveAndFlush(reloaded);

        User afterDisable = users.findById(user.getId()).orElseThrow();
        assertThat(afterDisable.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(afterDisable.getTokenVersion()).isEqualTo(before + 2);
    }
}
