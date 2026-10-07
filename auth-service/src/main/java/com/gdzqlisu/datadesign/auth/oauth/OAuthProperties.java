package com.gdzqlisu.datadesign.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.oauth")
public record OAuthProperties(GitHub github, String consoleBaseUrl) {

    public record GitHub(String clientId, String clientSecret, String authorizeUrl,
                         String tokenUrl, String apiBaseUrl, String redirectUri) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
