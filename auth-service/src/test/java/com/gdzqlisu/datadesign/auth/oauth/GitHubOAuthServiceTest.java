package com.gdzqlisu.datadesign.auth.oauth;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用 JDK 自带的 HttpServer 起一个假 GitHub，零额外依赖地验证真实的 HTTP 交互：
 * 授权 URL 的组装、令牌请求的表单体、以及 profile 的归一化。
 */
@SpringBootTest
class GitHubOAuthServiceTest extends IntegrationTestBase {

    private static final HttpServer STUB = startStub();
    private static final int STUB_PORT = STUB.getAddress().getPort();
    private static final AtomicReference<String> TOKEN_RESPONSE =
            new AtomicReference<>("{\"access_token\":\"gho_test_token\"}");
    private static final AtomicReference<String> LAST_TOKEN_BODY = new AtomicReference<>("");

    @Autowired
    private GitHubOAuthService service;

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/login/oauth/access_token", exchange -> {
                LAST_TOKEN_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, TOKEN_RESPONSE.get());
            });
            server.createContext("/user/emails", exchange -> respond(exchange,
                    "[{\"email\":\"octocat@github.com\",\"primary\":true,\"verified\":true}]"));
            server.createContext("/user", exchange -> respond(exchange,
                    "{\"id\":583231,\"login\":\"octocat\",\"name\":\"The Octocat\","
                            + "\"avatar_url\":\"https://avatars.example/octocat.png\",\"email\":null}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void githubProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.oauth.github.client-id", () -> "test-client-id");
        registry.add("auth.oauth.github.client-secret", () -> "test-client-secret");
        registry.add("auth.oauth.github.api-base-url", () -> "http://127.0.0.1:" + STUB_PORT);
        registry.add("auth.oauth.github.token-url", () -> "http://127.0.0.1:" + STUB_PORT + "/login/oauth/access_token");
        registry.add("auth.oauth.github.redirect-uri", () -> "http://localhost:5173/api/auth/github/callback");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetStub() {
        TOKEN_RESPONSE.set("{\"access_token\":\"gho_test_token\"}");
        LAST_TOKEN_BODY.set("");
    }

    @Test
    void authorizeUrlCarriesStateAndPkceChallenge() {
        String url = service.authorizeUrl("state-123", "challenge-abc");

        assertThat(url).startsWith("https://github.com/login/oauth/authorize");
        assertThat(url).contains("state=state-123");
        assertThat(url).contains("code_challenge=challenge-abc");
        assertThat(url).contains("code_challenge_method=S256");
        assertThat(url).contains("scope=read:user%20user:email");
        assertThat(url).contains("client_id=test-client-id");
    }

    @Test
    void exchangeNormalizesProfileAndFallsBackToPrimaryEmail() {
        GitHubProfile profile = service.exchange("code-abc", "verifier-xyz");

        assertThat(profile.providerUserId()).isEqualTo("583231");
        assertThat(profile.login()).isEqualTo("octocat");
        assertThat(profile.displayName()).isEqualTo("The Octocat");
        assertThat(profile.email()).isEqualTo("octocat@github.com");
        assertThat(profile.avatarUrl()).isEqualTo("https://avatars.example/octocat.png");
        assertThat(LAST_TOKEN_BODY.get()).contains("code_verifier=verifier-xyz");
        assertThat(LAST_TOKEN_BODY.get()).contains("client_secret=test-client-secret");
    }

    @Test
    void missingAccessTokenIsReportedAsApiFailure() {
        TOKEN_RESPONSE.set("{\"error\":\"bad_verification_code\"}");

        assertThatThrownBy(() -> service.exchange("bad-code", "verifier-xyz"))
                .isInstanceOf(GitHubApiException.class);
    }
}
