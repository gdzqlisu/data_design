package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

public class GitHubOAuthService {

    private static final String SCOPE = "read:user user:email";

    private final RestClient http;
    private final OAuthProperties.GitHub github;

    public GitHubOAuthService(RestClient.Builder builder, OAuthProperties properties) {
        this.github = properties.github();
        this.http = builder.build();
    }

    public boolean configured() {
        return github.configured();
    }

    public String authorizeUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(github.authorizeUrl())
                .queryParam("client_id", github.clientId())
                .queryParam("redirect_uri", github.redirectUri())
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
    }

    public GitHubProfile exchange(String code, String codeVerifier) {
        String accessToken = requestAccessToken(code, codeVerifier);
        GitHubUserResponse user = fetchUser(accessToken);

        String email = user.email();
        if (email == null || email.isBlank()) {
            email = fetchPrimaryEmail(accessToken);
        }
        String displayName = user.name() != null && !user.name().isBlank() ? user.name() : user.login();

        return new GitHubProfile(
                String.valueOf(user.id()),
                user.login(),
                displayName,
                email == null ? null : email.trim().toLowerCase(),
                user.avatarUrl());
    }

    private String requestAccessToken(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", github.clientId());
        form.add("client_secret", github.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", github.redirectUri());
        form.add("code_verifier", codeVerifier);

        AccessTokenResponse response;
        try {
            response = http.post()
                    .uri(github.tokenUrl())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AccessTokenResponse.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("与 GitHub 交换令牌失败", e);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new GitHubApiException("GitHub 未返回访问令牌，授权码可能已失效或已被使用");
        }
        return response.accessToken();
    }

    private GitHubUserResponse fetchUser(String accessToken) {
        GitHubUserResponse user;
        try {
            user = http.get()
                    .uri(github.apiBaseUrl() + "/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubUserResponse.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("读取 GitHub 用户信息失败", e);
        }
        if (user == null || user.id() == null) {
            throw new GitHubApiException("GitHub 未返回用户标识");
        }
        return user;
    }

    private String fetchPrimaryEmail(String accessToken) {
        GitHubEmailResponse[] emails;
        try {
            emails = http.get()
                    .uri(github.apiBaseUrl() + "/user/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubEmailResponse[].class);
        } catch (RestClientException e) {
            throw new GitHubApiException("读取 GitHub 邮箱失败", e);
        }
        if (emails == null) {
            return null;
        }
        for (GitHubEmailResponse candidate : emails) {
            if (candidate.primary() && candidate.verified()) {
                return candidate.email();
            }
        }
        for (GitHubEmailResponse candidate : emails) {
            if (candidate.verified()) {
                return candidate.email();
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubUserResponse(Long id, String login, String name,
                                     @JsonProperty("avatar_url") String avatarUrl,
                                     String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubEmailResponse(String email, boolean primary, boolean verified) {
    }
}
