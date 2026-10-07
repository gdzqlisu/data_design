package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.user.User;

import java.time.Instant;

public record UserSummaryResponse(Long id, String displayName, String email, String avatarUrl,
                                  String role, String status, Instant createdAt, Instant lastLoginAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getDisplayName(), user.getEmail(),
                user.getAvatarUrl(), user.getRole().name(), user.getStatus().name(),
                user.getCreatedAt(), user.getLastLoginAt());
    }
}
