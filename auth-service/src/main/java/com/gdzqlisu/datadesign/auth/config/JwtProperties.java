package com.gdzqlisu.datadesign.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, Duration accessTtl, String issuer) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "auth.jwt.secret 至少需要 32 字节，推荐用 openssl rand -base64 48 生成");
        }
        Objects.requireNonNull(accessTtl, "auth.jwt.access-ttl 未配置");
        Objects.requireNonNull(issuer, "auth.jwt.issuer 未配置");
    }
}
