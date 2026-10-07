package com.gdzqlisu.datadesign.auth.security;

import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存 users.token_version，避免每个请求都打数据库。
 * 改角色与禁用账号时必须调用 evict，让生效接近即时；60 秒 TTL 只是兜底。
 */
@Component
public class TokenVersionCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "tv:";

    private final StringRedisTemplate redis;
    private final UserRepository users;

    public TokenVersionCache(StringRedisTemplate redis, UserRepository users) {
        this.redis = redis;
        this.users = users;
    }

    public int currentTokenVersion(long userId) {
        String key = PREFIX + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return Integer.parseInt(cached);
        }
        int actual = users.findById(userId).map(User::getTokenVersion).orElse(-1);
        redis.opsForValue().set(key, Integer.toString(actual), TTL);
        return actual;
    }

    public void evict(long userId) {
        redis.delete(PREFIX + userId);
    }
}
