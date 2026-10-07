package com.gdzqlisu.datadesign.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditLogRepository;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private JwtService jwt;

    @Autowired
    private ObjectMapper mapper;

    private User create(Role role, UserStatus status) {
        User user = User.newPending("u-" + UUID.randomUUID(), null, null);
        if (status != UserStatus.PENDING) {
            user.approve(role, null);
        }
        if (status == UserStatus.DISABLED) {
            user.disable();
        }
        return users.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return "Bearer " + jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
    }

    @Test
    void pendingListIsReachableForAdmin() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);
        User applicant = create(Role.MEMBER, UserStatus.PENDING);

        mvc.perform(get("/api/admin/users").param("status", "PENDING")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + applicant.getId() + ")]").exists());
    }

    @Test
    void memberCannotReachAdminApi() throws Exception {
        User member = create(Role.MEMBER, UserStatus.ACTIVE);

        mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, tokenFor(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveActivatesUserWithChosenRole() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);
        User applicant = create(Role.MEMBER, UserStatus.PENDING);

        mvc.perform(post("/api/admin/users/" + applicant.getId() + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ApproveRequest(Role.STRATEGIST))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("STRATEGIST"));

        assertThatUser(applicant.getId()).hasStatus(UserStatus.ACTIVE)
                .hasRole(Role.STRATEGIST)
                .wasApprovedBy(admin.getId());
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(applicant.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.APPROVED);
    }

    @Test
    void rejectMarksApplicantRejected() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);
        User applicant = create(Role.MEMBER, UserStatus.PENDING);

        mvc.perform(post("/api/admin/users/" + applicant.getId() + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void changingAnotherUsersRoleInvalidatesTheirExistingToken() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);
        User member = create(Role.MEMBER, UserStatus.ACTIVE);
        String memberToken = tokenFor(member);

        mvc.perform(post("/api/admin/users/" + member.getId() + "/role")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RoleChangeRequest(Role.VIEWER))))
                .andExpect(status().isOk());

        // 改角色只动 token_version，账号仍 ACTIVE，所以是 401 token_revoked。
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_revoked"));
    }

    @Test
    void disablingAnotherUserInvalidatesTheirExistingToken() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);
        User member = create(Role.MEMBER, UserStatus.ACTIVE);
        String memberToken = tokenFor(member);

        mvc.perform(post("/api/admin/users/" + member.getId() + "/disable")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // 403 而不是 401：禁用账号会自增 token_version，但过滤器先判账号状态，
        // 这样前端能区分「账号被停用」和「令牌过期需重新登录」。
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_not_active"));
    }

    @Test
    void adminCannotDisableSelf() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);

        mvc.perform(post("/api/admin/users/" + admin.getId() + "/disable")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cannot_modify_self"));
    }

    @Test
    void adminCannotChangeOwnRole() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);

        mvc.perform(post("/api/admin/users/" + admin.getId() + "/role")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RoleChangeRequest(Role.MEMBER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cannot_modify_self"));
    }

    @Test
    void approvingUnknownUserReturnsNotFound() throws Exception {
        User admin = create(Role.ADMIN, UserStatus.ACTIVE);

        mvc.perform(post("/api/admin/users/999999999/approve")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ApproveRequest(Role.MEMBER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user_not_found"));
    }

    private UserAssertion assertThatUser(Long id) {
        return new UserAssertion(users.findById(id).orElseThrow());
    }

    private record UserAssertion(User user) {
        UserAssertion hasStatus(UserStatus expected) {
            org.assertj.core.api.Assertions.assertThat(user.getStatus()).isEqualTo(expected);
            return this;
        }

        UserAssertion hasRole(Role expected) {
            org.assertj.core.api.Assertions.assertThat(user.getRole()).isEqualTo(expected);
            return this;
        }

        UserAssertion wasApprovedBy(Long adminId) {
            org.assertj.core.api.Assertions.assertThat(user.getApprovedBy()).isEqualTo(adminId);
            return this;
        }
    }
}
