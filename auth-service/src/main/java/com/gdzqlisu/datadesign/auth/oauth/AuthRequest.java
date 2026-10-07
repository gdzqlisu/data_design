package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthRequest(String codeVerifier) {
}
