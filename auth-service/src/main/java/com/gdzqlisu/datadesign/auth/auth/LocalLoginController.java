package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.config.JwtProperties;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.security.LoginRateLimiter;
import com.gdzqlisu.datadesign.auth.token.RefreshCookieService;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/local")
public class LocalLoginController {

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookieService cookies;
    private final JwtService jwt;
    private final JwtProperties jwtProperties;
    private final LoginRateLimiter rateLimiter;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;

    public LocalLoginController(UserRepository users,
                                RefreshTokenService refreshTokens,
                                RefreshCookieService cookies,
                                JwtService jwt,
                                JwtProperties jwtProperties,
                                LoginRateLimiter rateLimiter,
                                AuditService audit,
                                PasswordEncoder passwordEncoder) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.jwt = jwt;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LocalLoginRequest body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        String ip = AuditService.clientIp(request);
        if (!rateLimiter.tryAcquire("ip:" + ip) || !rateLimiter.tryAcquire("user:" + body.username())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "登录尝试过于频繁，请 15 分钟后再试");
        }

        User user = users.findByDisplayName(body.username())
                .filter(User::isBreakGlass)
                .orElse(null);

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            audit.recordFromRequest(user == null ? null : user.getId(), AuditEvent.LOGIN_FAILED,
                    "LOCAL", request, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "用户名或密码不正确");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            audit.recordFromRequest(user.getId(), AuditEvent.LOGIN_DISABLED, "LOCAL", request, null);
            throw new ApiException(HttpStatus.FORBIDDEN, "account_not_active", "账号当前不可用");
        }

        rateLimiter.reset("user:" + body.username());
        user.recordLogin();
        users.saveAndFlush(user);
        audit.recordFromRequest(user.getId(), AuditEvent.BREAK_GLASS_LOGIN, "LOCAL", request, null);

        cookies.write(response, refreshTokens.issue(user.getId(), user.getTokenVersion(),
                request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent")));
        String accessToken = jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
        return ResponseEntity.ok(new TokenResponse(accessToken,
                jwtProperties.accessTtl().toSeconds(), user.getRole().name(), user.getStatus().name()));
    }
}
