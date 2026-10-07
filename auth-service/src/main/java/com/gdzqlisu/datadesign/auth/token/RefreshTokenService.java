package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

public class RefreshTokenService {

    private static final String RECORD_PREFIX = "rt:";
    private static final String USER_SET_PREFIX = "rtu:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public IssuedRefreshToken issue(long userId, int tokenVersion, String userAgent) {
        String jti = UUID.randomUUID().toString();
        String secret = randomSecret();
        Instant now = Instant.now();
        RefreshTokenRecord record = new RefreshTokenRecord(
                jti, sha256(secret), userId, tokenVersion, sha256(userAgent == null ? "" : userAgent),
                now, now.plus(ttl), null);
        save(record);
        String userKey = USER_SET_PREFIX + userId;
        redis.opsForSet().add(userKey, jti);
        redis.expire(userKey, ttl);
        return new IssuedRefreshToken(jti + "." + secret, jti, userId, record.expiresAt());
    }

    /**
     * 只校验不轮换，供 /api/auth/session 这类只读查询使用。
     * 轮换式校验会让并发的两个标签页互相把对方判成盗用。
     */
    public RefreshTokenRecord inspect(String rawToken) {
        String[] parts = split(rawToken);
        RefreshTokenRecord record = load(parts[0]);
        if (record == null) {
            throw new InvalidRefreshTokenException("刷新令牌不存在、已过期或已被撤销");
        }
        if (!MessageDigest.isEqual(
                record.secretHash().getBytes(StandardCharsets.UTF_8),
                sha256(parts[1]).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidRefreshTokenException("刷新令牌校验失败");
        }
        if (record.rotatedTo() != null) {
            revokeAllForUser(record.userId());
            throw new RefreshTokenReuseException(record.userId());
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            delete(parts[0]);
            throw new InvalidRefreshTokenException("刷新令牌已过期");
        }
        return record;
    }

    public IssuedRefreshToken rotate(String rawToken, String userAgent) {
        RefreshTokenRecord existing = inspect(rawToken);
        IssuedRefreshToken next = issue(existing.userId(), existing.tokenVersion(), userAgent);
        save(existing.withRotatedTo(next.jti()));
        return next;
    }

    public void revoke(String rawToken) {
        String[] parts;
        try {
            parts = split(rawToken);
        } catch (InvalidRefreshTokenException ignored) {
            return;
        }
        RefreshTokenRecord record = load(parts[0]);
        delete(parts[0]);
        if (record != null) {
            redis.opsForSet().remove(USER_SET_PREFIX + record.userId(), parts[0]);
        }
    }

    public void revokeAllForUser(long userId) {
        Set<String> jtis = redis.opsForSet().members(USER_SET_PREFIX + userId);
        if (jtis != null && !jtis.isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (String jti : jtis) {
                keys.add(RECORD_PREFIX + jti);
            }
            redis.delete(keys);
        }
        redis.delete(USER_SET_PREFIX + userId);
    }

    private String[] split(String rawToken) {
        if (rawToken == null) {
            throw new InvalidRefreshTokenException("刷新令牌为空");
        }
        int dot = rawToken.indexOf('.');
        if (dot <= 0 || dot == rawToken.length() - 1) {
            throw new InvalidRefreshTokenException("刷新令牌格式不正确");
        }
        return new String[]{rawToken.substring(0, dot), rawToken.substring(dot + 1)};
    }

    private RefreshTokenRecord load(String jti) {
        String json = redis.opsForValue().get(RECORD_PREFIX + jti);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, RefreshTokenRecord.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRefreshTokenException("刷新令牌记录无法解析");
        }
    }

    private void save(RefreshTokenRecord record) {
        try {
            redis.opsForValue().set(RECORD_PREFIX + record.jti(), mapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("刷新令牌记录无法序列化", e);
        }
    }

    private void delete(String jti) {
        redis.delete(RECORD_PREFIX + jti);
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
