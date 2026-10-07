package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.security.AuthenticatedUser;
import com.gdzqlisu.datadesign.auth.security.TokenVersionCache;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class UserAdminService {

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final TokenVersionCache tokenVersions;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RefreshTokenService refreshTokens,
                            TokenVersionCache tokenVersions, AuditService audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokenVersions = tokenVersions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listByStatus(UserStatus status) {
        return users.findAllByStatusOrderByCreatedAtAsc(status).stream()
                .map(UserSummaryResponse::from)
                .toList();
    }

    @Transactional
    public UserSummaryResponse approve(long userId, Role role, AuthenticatedUser admin,
                                       HttpServletRequest request) {
        User user = require(userId);
        user.approve(role, admin.id());
        users.saveAndFlush(user);
        audit.recordFromRequest(userId, AuditEvent.APPROVED, "LOCAL", request,
                Map.of("role", role.name(), "adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse reject(long userId, AuthenticatedUser admin, HttpServletRequest request) {
        User user = require(userId);
        user.reject();
        users.saveAndFlush(user);
        audit.recordFromRequest(userId, AuditEvent.REJECTED, "LOCAL", request,
                Map.of("adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse changeRole(long userId, Role role, AuthenticatedUser admin,
                                          HttpServletRequest request) {
        requireNotSelf(userId, admin, "不能修改自己的角色");
        User user = require(userId);
        user.changeRole(role);
        users.saveAndFlush(user);
        invalidateSessions(userId);
        audit.recordFromRequest(userId, AuditEvent.ROLE_CHANGED, "LOCAL", request,
                Map.of("role", role.name(), "adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse disable(long userId, AuthenticatedUser admin, HttpServletRequest request) {
        requireNotSelf(userId, admin, "不能禁用自己的账号");
        User user = require(userId);
        user.disable();
        users.saveAndFlush(user);
        invalidateSessions(userId);
        audit.recordFromRequest(userId, AuditEvent.USER_DISABLED, "LOCAL", request,
                Map.of("adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    private void invalidateSessions(long userId) {
        tokenVersions.evict(userId);
        refreshTokens.revokeAllForUser(userId);
    }

    private void requireNotSelf(long userId, AuthenticatedUser admin, String message) {
        if (admin.id() != null && admin.id() == userId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_modify_self", message);
        }
    }

    private User require(long userId) {
        return users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "账号不存在"));
    }
}
