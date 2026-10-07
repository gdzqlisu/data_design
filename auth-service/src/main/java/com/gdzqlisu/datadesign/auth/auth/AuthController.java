package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.config.JwtProperties;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.oauth.AuthRequest;
import com.gdzqlisu.datadesign.auth.oauth.GitHubApiException;
import com.gdzqlisu.datadesign.auth.oauth.GitHubOAuthService;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import com.gdzqlisu.datadesign.auth.oauth.OAuthProperties;
import com.gdzqlisu.datadesign.auth.oauth.OAuthStateStore;
import com.gdzqlisu.datadesign.auth.token.InvalidRefreshTokenException;
import com.gdzqlisu.datadesign.auth.token.IssuedRefreshToken;
import com.gdzqlisu.datadesign.auth.token.RefreshCookieService;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenRecord;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenReuseException;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.LoginOutcome;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserProvisioningService;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final GitHubOAuthService github;
    private final OAuthStateStore stateStore;
    private final OAuthProperties oauthProperties;
    private final UserProvisioningService provisioning;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookieService cookies;
    private final JwtService jwt;
    private final JwtProperties jwtProperties;
    private final UserRepository users;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AuthController(GitHubOAuthService github,
                          OAuthStateStore stateStore,
                          OAuthProperties oauthProperties,
                          UserProvisioningService provisioning,
                          RefreshTokenService refreshTokens,
                          RefreshCookieService cookies,
                          JwtService jwt,
                          JwtProperties jwtProperties,
                          UserRepository users,
                          AuditService audit) {
        this.github = github;
        this.stateStore = stateStore;
        this.oauthProperties = oauthProperties;
        this.provisioning = provisioning;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.jwt = jwt;
        this.jwtProperties = jwtProperties;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/github/authorize")
    public ResponseEntity<Void> authorize() {
        if (!github.configured()) {
            return redirect("/login?error=provider_not_configured");
        }
        String state = UUID.randomUUID().toString();
        String verifier = randomUrlSafe(32);
        stateStore.save(state, new AuthRequest(verifier));
        return redirectTo(github.authorizeUrl(state, codeChallenge(verifier)));
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        AuthRequest authRequest = stateStore.consume(state);
        if (authRequest == null) {
            return redirect("/login?error=state_expired");
        }
        if (error != null || code == null || code.isBlank()) {
            return redirect("/login?error=oauth_failed");
        }

        GitHubProfile profile;
        try {
            profile = github.exchange(code, authRequest.codeVerifier());
        } catch (GitHubApiException e) {
            return redirect("/login?error=oauth_failed");
        }

        LoginOutcome outcome = provisioning.login(profile, request);
        User user = outcome.user();

        if (user.getStatus() == UserStatus.DISABLED) {
            cookies.clear(response);
            return redirect("/login?error=disabled");
        }

        cookies.write(response, refreshTokens.issue(user.getId(), user.getTokenVersion(), userAgent(request)));
        return switch (user.getStatus()) {
            case ACTIVE -> redirect("/auth/callback");
            case PENDING -> redirect("/pending");
            case REJECTED -> redirect("/rejected");
            case DISABLED -> redirect("/login?error=disabled");
        };
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request) {
        User user = requireCookieUser(request);
        return ResponseEntity.ok(new SessionResponse(
                user.getId(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().toString()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        requireSameOrigin(request);
        String raw = cookies.read(request).orElseThrow(() -> unauthorized("unauthenticated", "缺少刷新令牌"));

        IssuedRefreshToken rotated;
        try {
            rotated = refreshTokens.rotate(raw, userAgent(request));
        } catch (RefreshTokenReuseException e) {
            cookies.clear(response);
            audit.recordFromRequest(e.getUserId(), AuditEvent.TOKEN_REVOKED, "LOCAL", request,
                    Map.of("reason", "refresh_token_reuse"));
            throw unauthorized("token_reuse", "刷新令牌被重复使用，已撤销该账号的全部会话");
        } catch (InvalidRefreshTokenException e) {
            cookies.clear(response);
            throw unauthorized("invalid_refresh_token", e.getMessage());
        }

        User user = users.findById(rotated.userId()).orElseThrow(
                () -> unauthorized("user_not_found", "账号不存在"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            cookies.clear(response);
            throw new ApiException(HttpStatus.FORBIDDEN, "account_not_active", "账号尚未通过审批或已被停用");
        }

        cookies.write(response, rotated);
        String accessToken = jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
        return ResponseEntity.ok(new TokenResponse(accessToken,
                jwtProperties.accessTtl().toSeconds(), user.getRole().name(), user.getStatus().name()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        cookies.read(request).ifPresent(raw -> {
            try {
                RefreshTokenRecord record = refreshTokens.inspect(raw);
                refreshTokens.revoke(raw);
                audit.recordFromRequest(record.userId(), AuditEvent.LOGOUT, "LOCAL", request, null);
            } catch (RuntimeException ignored) {
                refreshTokens.revoke(raw);
            }
        });
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    private User requireCookieUser(HttpServletRequest request) {
        String raw = cookies.read(request).orElseThrow(() -> unauthorized("unauthenticated", "缺少会话"));
        RefreshTokenRecord record;
        try {
            record = refreshTokens.inspect(raw);
        } catch (RefreshTokenReuseException e) {
            throw unauthorized("token_reuse", "刷新令牌被重复使用，请重新登录");
        } catch (InvalidRefreshTokenException e) {
            throw unauthorized("invalid_refresh_token", e.getMessage());
        }
        return users.findById(record.userId()).orElseThrow(() -> unauthorized("user_not_found", "账号不存在"));
    }

    private ResponseEntity<Void> redirect(String consolePath) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthProperties.consoleBaseUrl() + consolePath))
                .build();
    }

    private ResponseEntity<Void> redirectTo(String absoluteUrl) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(absoluteUrl)).build();
    }

    /**
     * refresh token 走 Cookie，必须确认请求确实来自我们自己的前端 origin。
     * 浏览器跨站发起的请求一定带 Origin，不同源直接拒绝；
     * curl、测试这类非浏览器客户端不带 Origin，放行。
     */
    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        String allowed = oauthProperties.consoleBaseUrl();
        if (allowed == null
                || !origin.replaceAll("/+$", "").equalsIgnoreCase(allowed.replaceAll("/+$", ""))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "cross_origin", "刷新令牌只能由同源前端发起");
        }
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        return agent == null ? "" : agent;
    }

    private String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
