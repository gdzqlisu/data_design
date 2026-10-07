package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "auth.oauth.github.client-id=test-client-id",
        "auth.oauth.github.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenService refreshTokens;

    private User userWithStatus(String displayName, Role role, boolean active) {
        User user = User.newPending(displayName, null, null);
        if (active) {
            user.approve(role, null);
        }
        return users.saveAndFlush(user);
    }

    private Cookie refreshCookie(User user) {
        return new Cookie("ds_rt", refreshTokens.issue(user.getId(), user.getTokenVersion(), "JUnit").rawToken());
    }

    @Test
    void authorizeRedirectsToGitHubWhenConfigured() throws Exception {
        mvc.perform(get("/api/auth/github/authorize"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("github.com/login/oauth/authorize")));
    }

    @Test
    void callbackWithUnknownStateRedirectsToLoginError() throws Exception {
        mvc.perform(get("/api/auth/github/callback").param("code", "x").param("state", "forged"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login?error=state_expired")));
    }

    @Test
    void sessionReturnsPendingStatusForUnapprovedUser() throws Exception {
        User pending = userWithStatus("pending-" + UUID.randomUUID(), Role.MEMBER, false);

        mvc.perform(get("/api/auth/session").cookie(refreshCookie(pending)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void refreshIssuesAccessTokenOnlyForActiveUser() throws Exception {
        User active = userWithStatus("active-" + UUID.randomUUID(), Role.MEMBER, true);

        mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(active)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(cookie().exists("ds_rt"));
    }

    @Test
    void refreshRefusesPendingUser() throws Exception {
        User pending = userWithStatus("pending-" + UUID.randomUUID(), Role.MEMBER, false);

        mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(pending)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_not_active"));
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsCrossOriginRequest() throws Exception {
        User active = userWithStatus("cross-origin-" + UUID.randomUUID(), Role.MEMBER, true);

        mvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie(active))
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("cross_origin"));
    }

    @Test
    void refreshDetectsTokenReuseAndRevokesChain() throws Exception {
        User active = userWithStatus("reuse-" + UUID.randomUUID(), Role.MEMBER, true);
        Cookie stolen = refreshCookie(active);

        mvc.perform(post("/api/auth/refresh").cookie(stolen)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/refresh").cookie(stolen))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_reuse"));
    }

    @Test
    void logoutClearsCookie() throws Exception {
        User active = userWithStatus("logout-" + UUID.randomUUID(), Role.MEMBER, true);

        mvc.perform(post("/api/auth/logout").cookie(refreshCookie(active)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("ds_rt", 0));
    }
}
