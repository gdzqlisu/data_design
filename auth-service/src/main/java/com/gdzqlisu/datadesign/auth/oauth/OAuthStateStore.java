package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 暂存 OAuth2 授权请求。consume 用 getAndDelete，取出即删，天然防重放。
 */
@Component
public class OAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "oauth2:authreq:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OAuthStateStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void save(String state, AuthRequest request) {
        try {
            redis.opsForValue().set(PREFIX + state, mapper.writeValueAsString(request), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("授权请求无法序列化", e);
        }
    }

    public AuthRequest consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().getAndDelete(PREFIX + state);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, AuthRequest.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
