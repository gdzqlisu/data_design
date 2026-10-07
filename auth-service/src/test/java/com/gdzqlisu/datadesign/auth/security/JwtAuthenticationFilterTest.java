package com.gdzqlisu.datadesign.auth.security;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwt;

    @Autowired
    private TokenVersionCache tokenVersions;

    private User activeUser(Role role) {
        User user = User.newPending("u-" + UUID.randomUUID(), null, null);
        user.approve(role, null);
        return users.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenExposesPrincipal() throws Exception {
        User user = activeUser(Role.MEMBER);

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void tokenWithStaleVersionIsUnauthorized() throws Exception {
        User user = activeUser(Role.MEMBER);
        String token = bearer(user);

        user.changeRole(Role.VIEWER);
        users.saveAndFlush(user);
        tokenVersions.evict(user.getId());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserIsForbidden() throws Exception {
        User user = activeUser(Role.MEMBER);
        String token = bearer(user);

        user.disable();
        users.saveAndFlush(user);
        tokenVersions.evict(user.getId());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenOfUnknownUserIsUnauthorized() throws Exception {
        String token = "Bearer " + jwt.issue(999_999_999L, "MEMBER", 0);

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }
}
