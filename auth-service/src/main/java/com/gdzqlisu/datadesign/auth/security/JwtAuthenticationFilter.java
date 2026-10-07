package com.gdzqlisu.datadesign.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.config.AccessTokenClaims;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository users;
    private final TokenVersionCache tokenVersions;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users,
                                   TokenVersionCache tokenVersions, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.users = users;
        this.tokenVersions = tokenVersions;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        AccessTokenClaims claims;
        try {
            claims = jwtService.parse(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", "访问令牌无效或已过期");
            return;
        }

        User user = users.findById(claims.userId()).orElse(null);
        if (user == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "user_not_found", "账号不存在");
            return;
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "account_not_active",
                    "账号当前状态为 " + user.getStatus() + "，无法访问");
            return;
        }

        if (tokenVersions.currentTokenVersion(claims.userId()) != claims.tokenVersion()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "token_revoked", "访问令牌已被撤销，请重新登录");
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(), user.getDisplayName(), user.getRole().name(), user.getTokenVersion());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }
}
