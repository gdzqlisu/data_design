package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshTokenRecord(
        String jti,
        String secretHash,
        long userId,
        int tokenVersion,
        String userAgentHash,
        Instant createdAt,
        Instant expiresAt,
        String rotatedTo) {

    public RefreshTokenRecord withRotatedTo(String newJti) {
        return new RefreshTokenRecord(jti, secretHash, userId, tokenVersion, userAgentHash,
                createdAt, expiresAt, newJti);
    }
}
