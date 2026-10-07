package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.user.Role;
import jakarta.validation.constraints.NotNull;

public record ApproveRequest(@NotNull Role role) {
}
