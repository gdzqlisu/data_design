package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.security.AuthenticatedUser;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = users.findById(principal.id()).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "displayName", user.getDisplayName() == null ? "" : user.getDisplayName(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl(),
                "role", user.getRole().name(),
                "status", user.getStatus().name(),
                "breakGlass", user.isBreakGlass()));
    }
}
