package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RefreshTokenConfig {

    @Bean
    public RefreshTokenService refreshTokenService(StringRedisTemplate redis,
                                                   ObjectMapper mapper,
                                                   @Value("${auth.refresh-ttl}") Duration ttl) {
        return new RefreshTokenService(redis, mapper, ttl);
    }
}
