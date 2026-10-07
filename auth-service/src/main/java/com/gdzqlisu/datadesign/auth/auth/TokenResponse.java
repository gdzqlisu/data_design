package com.gdzqlisu.datadesign.auth.auth;

public record TokenResponse(String accessToken, long expiresInSeconds, String role, String status) {
}
