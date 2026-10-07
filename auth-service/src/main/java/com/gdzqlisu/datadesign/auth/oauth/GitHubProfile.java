package com.gdzqlisu.datadesign.auth.oauth;

/**
 * 从 GitHub 拉回来的原始信息，已归一化：邮箱统一小写，displayName 一定非空。
 */
public record GitHubProfile(String providerUserId, String login, String displayName,
                            String email, String avatarUrl) {
}
