package com.gdzqlisu.datadesign.auth.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Component
public class RefreshCookieService {

    public static final String COOKIE_NAME = "ds_rt";
    private static final String COOKIE_PATH = "/api/auth";

    private final Duration ttl;
    private final boolean secure;

    public RefreshCookieService(@Value("${auth.refresh-ttl}") Duration ttl,
                                @Value("${auth.cookie-secure:false}") boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    public void write(HttpServletResponse response, IssuedRefreshToken token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(token.rawToken(), ttl.toSeconds()));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", 0));
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private String build(String value, long maxAgeSeconds) {
        StringBuilder builder = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(value)
                .append("; Path=").append(COOKIE_PATH)
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }
}
