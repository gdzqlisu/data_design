package com.gdzqlisu.datadesign.auth.oauth;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OAuthStateStoreTest extends IntegrationTestBase {

    @Autowired
    private OAuthStateStore store;

    @Test
    void savedRequestCanBeConsumedExactlyOnce() {
        String state = UUID.randomUUID().toString();
        store.save(state, new AuthRequest("verifier-abc"));

        AuthRequest consumed = store.consume(state);

        assertThat(consumed).isNotNull();
        assertThat(consumed.codeVerifier()).isEqualTo("verifier-abc");
        assertThat(store.consume(state)).isNull();
    }

    @Test
    void unknownStateReturnsNull() {
        assertThat(store.consume("never-issued")).isNull();
    }
}
