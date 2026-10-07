package com.gdzqlisu.datadesign.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.break-glass")
public record BreakGlassAdminProperties(String username, String passwordHash) {

    public boolean configured() {
        return username != null && !username.isBlank()
                && passwordHash != null && !passwordHash.isBlank();
    }
}
