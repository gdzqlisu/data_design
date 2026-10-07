package com.gdzqlisu.datadesign.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 排除 Spring Security 的内存用户自动配置：认证完全走自研的 JWT 过滤器与破窗登录，
// 留着它只会在每次启动时打印一个谁也用不上的随机密码，徒增运维困惑。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
