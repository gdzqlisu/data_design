package com.gdzqlisu.datadesign.auth.token;

import java.time.Instant;

/**
 * rawToken 是写进 Cookie 的值，形如 {jti}.{secret}，只在签发时可见一次。
 */
public record IssuedRefreshToken(String rawToken, String jti, long userId, Instant expiresAt) {
}
