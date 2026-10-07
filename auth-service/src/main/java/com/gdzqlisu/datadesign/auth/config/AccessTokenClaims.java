package com.gdzqlisu.datadesign.auth.config;

import java.time.Instant;

public record AccessTokenClaims(Long userId, String role, int tokenVersion, String jti, Instant expiresAt) {
}
