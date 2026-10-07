package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.security.AuthenticatedUser;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdminService admin;

    public AdminUserController(UserAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<UserSummaryResponse> list(@RequestParam(defaultValue = "PENDING") UserStatus status) {
        return admin.listByStatus(status);
    }

    @PostMapping("/{id}/approve")
    public UserSummaryResponse approve(@PathVariable long id,
                                       @Valid @RequestBody ApproveRequest body,
                                       @AuthenticationPrincipal AuthenticatedUser principal,
                                       HttpServletRequest request) {
        return admin.approve(id, body.role(), principal, request);
    }

    @PostMapping("/{id}/reject")
    public UserSummaryResponse reject(@PathVariable long id,
                                      @AuthenticationPrincipal AuthenticatedUser principal,
                                      HttpServletRequest request) {
        return admin.reject(id, principal, request);
    }

    @PostMapping("/{id}/role")
    public UserSummaryResponse changeRole(@PathVariable long id,
                                          @Valid @RequestBody RoleChangeRequest body,
                                          @AuthenticationPrincipal AuthenticatedUser principal,
                                          HttpServletRequest request) {
        return admin.changeRole(id, body.role(), principal, request);
    }

    @PostMapping("/{id}/disable")
    public UserSummaryResponse disable(@PathVariable long id,
                                       @AuthenticationPrincipal AuthenticatedUser principal,
                                       HttpServletRequest request) {
        return admin.disable(id, principal, request);
    }
}
