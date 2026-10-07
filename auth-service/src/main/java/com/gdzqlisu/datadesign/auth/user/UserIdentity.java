package com.gdzqlisu.datadesign.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "provider_login")
    private String providerLogin;

    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserIdentity() {
    }

    public static UserIdentity github(User user, String providerUserId, String providerLogin,
                                      String email, String avatarUrl) {
        UserIdentity identity = new UserIdentity();
        identity.userId = user.getId();
        identity.provider = AuthProvider.GITHUB;
        identity.providerUserId = providerUserId;
        identity.providerLogin = providerLogin;
        identity.email = email;
        identity.avatarUrl = avatarUrl;
        identity.touch();
        return identity;
    }

    public static UserIdentity local(User user) {
        UserIdentity identity = new UserIdentity();
        identity.userId = user.getId();
        identity.provider = AuthProvider.LOCAL;
        identity.providerUserId = String.valueOf(user.getId());
        identity.touch();
        return identity;
    }

    public void refreshProfile(String login, String newEmail, String newAvatarUrl) {
        if (login != null) {
            this.providerLogin = login;
        }
        if (newEmail != null) {
            this.email = newEmail;
        }
        if (newAvatarUrl != null) {
            this.avatarUrl = newAvatarUrl;
        }
        this.updatedAt = Instant.now();
    }

    private void touch() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getProviderLogin() {
        return providerLogin;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
