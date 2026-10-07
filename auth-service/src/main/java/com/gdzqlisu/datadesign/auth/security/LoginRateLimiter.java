package com.gdzqlisu.datadesign.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录尝试限流。计数器放 Redis，多实例部署也能生效。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String PREFIX = "rl:login:";

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true 表示本次尝试允许放行，false 表示已超限
     */
    public boolean tryAcquire(String scope) {
        String key = PREFIX + scope;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count == null || count <= MAX_ATTEMPTS;
    }

    public void reset(String scope) {
        redis.delete(PREFIX + scope);
    }
}
