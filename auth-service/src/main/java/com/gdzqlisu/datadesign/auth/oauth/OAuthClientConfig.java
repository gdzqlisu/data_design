package com.gdzqlisu.datadesign.auth.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OAuthClientConfig {

    @Bean
    public GitHubOAuthService gitHubOAuthService(RestClient.Builder builder, OAuthProperties properties) {
        return new GitHubOAuthService(builder, properties);
    }
}
