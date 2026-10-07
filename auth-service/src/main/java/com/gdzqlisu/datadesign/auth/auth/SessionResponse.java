package com.gdzqlisu.datadesign.auth.auth;

public record SessionResponse(Long id, String displayName, String avatarUrl, String role,
                              String status, String appliedAt) {
}
