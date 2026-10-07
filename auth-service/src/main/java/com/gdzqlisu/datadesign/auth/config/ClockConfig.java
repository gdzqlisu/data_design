package com.gdzqlisu.datadesign.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * 统一注入 Clock，测试里换成固定时钟即可验证过期逻辑。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
