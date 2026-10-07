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
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name")
    private String displayName;

    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role = Role.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "is_break_glass", nullable = false)
    private boolean breakGlass;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    private User(String displayName, String email, String avatarUrl) {
        this.displayName = displayName;
        this.email = email;
        this.avatarUrl = avatarUrl;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User newPending(String displayName, String email, String avatarUrl) {
        return new User(displayName, email, avatarUrl);
    }

    public static User newBreakGlass(String displayName, String passwordHash) {
        User user = new User(displayName, null, null);
        user.breakGlass = true;
        user.passwordHash = passwordHash;
        user.role = Role.ADMIN;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    /**
     * 每次启动按配置校正破窗账号。返回是否有字段被改动。
     */
    public boolean repairAsBreakGlass(String configuredPasswordHash) {
        boolean changed = false;
        if (this.role != Role.ADMIN) {
            this.role = Role.ADMIN;
            changed = true;
        }
        if (this.status != UserStatus.ACTIVE) {
            this.status = UserStatus.ACTIVE;
            changed = true;
        }
        if (!this.breakGlass) {
            this.breakGlass = true;
            changed = true;
        }
        if (configuredPasswordHash != null && !configuredPasswordHash.equals(this.passwordHash)) {
            this.passwordHash = configuredPasswordHash;
            changed = true;
        }
        if (changed) {
            this.updatedAt = Instant.now();
        }
        return changed;
    }

    public void approve(Role grantedRole, Long approverId) {
        this.role = grantedRole;
        this.status = UserStatus.ACTIVE;
        this.approvedBy = approverId;
        this.approvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reject() {
        this.status = UserStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
        bumpTokenVersion();
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
        bumpTokenVersion();
    }

    public void bumpTokenVersion() {
        this.tokenVersion++;
        this.updatedAt = Instant.now();
    }

    public void recordLogin() {
        this.lastLoginAt = Instant.now();
        this.updatedAt = this.lastLoginAt;
    }

    public void refreshProfile(String newDisplayName, String newAvatarUrl, String newEmail) {
        if (newDisplayName != null) {
            this.displayName = newDisplayName;
        }
        if (newAvatarUrl != null) {
            this.avatarUrl = newAvatarUrl;
        }
        if (this.email == null) {
            this.email = newEmail;
        }
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isBreakGlass() {
        return breakGlass;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
