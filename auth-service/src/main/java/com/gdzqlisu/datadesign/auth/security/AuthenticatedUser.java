package com.gdzqlisu.datadesign.auth.security;

public record AuthenticatedUser(Long id, String displayName, String role, int tokenVersion) {
}
