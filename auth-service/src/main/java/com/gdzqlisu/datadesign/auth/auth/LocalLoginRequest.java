package com.gdzqlisu.datadesign.auth.auth;

import jakarta.validation.constraints.NotBlank;

public record LocalLoginRequest(@NotBlank String username, @NotBlank String password) {
}
