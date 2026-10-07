package com.gdzqlisu.datadesign.auth.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditLogRepository;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.break-glass.username=break-glass-admin",
        "auth.break-glass.password-hash=$2a$10$wvDCi/W8YLzoJ/ZIZOLuKODuTUZ.WJpK3mLNaTOpEii6VG4G/81Dy"
})
@AutoConfigureMockMvc
class LocalLoginTest extends IntegrationTestBase {

    private static final String CORRECT_PASSWORD = "password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditLogRepository auditLogs;

    private String payload(String username, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    @Test
    void correctPasswordIssuesTokenAndCookie() throws Exception {
        mvc.perform(post("/api/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("break-glass-admin", CORRECT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(cookie().exists("ds_rt"));

        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(admin.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.BREAK_GLASS_LOGIN);
    }

    @Test
    void wrongPasswordIsRejectedAndAudited() throws Exception {
        mvc.perform(post("/api/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("break-glass-admin", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));

        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(admin.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_FAILED);
    }

    @Test
    void nonBreakGlassAccountCannotUseLocalLogin() throws Exception {
        User normal = User.newPending("normal-user-" + System.nanoTime(), null, null);
        normal.approve(com.gdzqlisu.datadesign.auth.user.Role.MEMBER, null);
        users.saveAndFlush(normal);

        mvc.perform(post("/api/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(normal.getDisplayName(), CORRECT_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitBlocksAfterTenFailuresFromSameIp() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            mvc.perform(post("/api/auth/local/login")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.77");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload("rate-limit-probe-" + System.nanoTime(), "wrong-password")))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 429));
        }

        MvcResult blocked = mvc.perform(post("/api/auth/local/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("rate-limit-probe-final", "wrong-password")))
                .andReturn();

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentAsString()).contains("too_many_attempts");
    }
}
