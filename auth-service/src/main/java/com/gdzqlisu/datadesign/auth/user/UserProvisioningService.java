package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class UserProvisioningService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final AuditService audit;

    public UserProvisioningService(UserRepository users, UserIdentityRepository identities, AuditService audit) {
        this.users = users;
        this.identities = identities;
        this.audit = audit;
    }

    @Transactional
    public LoginOutcome login(GitHubProfile profile, HttpServletRequest request) {
        Optional<User> existing =
                users.findByProviderAndProviderUserId(AuthProvider.GITHUB, profile.providerUserId());

        if (existing.isEmpty()) {
            User created = users.saveAndFlush(
                    User.newPending(profile.displayName(), profile.email(), profile.avatarUrl()));
            identities.saveAndFlush(UserIdentity.github(created, profile.providerUserId(),
                    profile.login(), profile.email(), profile.avatarUrl()));
            audit.recordFromRequest(created.getId(), AuditEvent.LOGIN_PENDING, "GITHUB", request,
                    Map.of("login", profile.login()));
            return new LoginOutcome(created, true);
        }

        User user = existing.get();
        user.refreshProfile(profile.displayName(), profile.avatarUrl(), profile.email());
        user.recordLogin();
        users.saveAndFlush(user);
        identities.findByProviderAndProviderUserId(AuthProvider.GITHUB, profile.providerUserId())
                .ifPresent(identity -> {
                    identity.refreshProfile(profile.login(), profile.email(), profile.avatarUrl());
                    identities.saveAndFlush(identity);
                });
        audit.recordFromRequest(user.getId(), auditEventFor(user.getStatus()), "GITHUB", request,
                Map.of("login", profile.login()));
        return new LoginOutcome(user, false);
    }

    private static AuditEvent auditEventFor(UserStatus status) {
        return switch (status) {
            case ACTIVE -> AuditEvent.LOGIN_SUCCESS;
            case PENDING -> AuditEvent.LOGIN_PENDING;
            case REJECTED -> AuditEvent.LOGIN_REJECTED;
            case DISABLED -> AuditEvent.LOGIN_DISABLED;
        };
    }
}
