package com.gdzqlisu.datadesign.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DatabaseMigrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationCreatesAllCoreTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables).contains("users", "user_identities", "audit_logs", "flyway_schema_history");
    }

    @Test
    void duplicateProviderIdentityIsRejected() {
        jdbc.update("INSERT INTO users (display_name, `role`, status, is_break_glass, token_version, created_at, updated_at) "
                + "VALUES ('a', 'MEMBER', 'ACTIVE', 0, 0, NOW(6), NOW(6))");
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE display_name = 'a'", Long.class);

        jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id, created_at, updated_at) "
                + "VALUES (?, 'GITHUB', '42', NOW(6), NOW(6))", userId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_identities (user_id, provider, provider_user_id, created_at, updated_at) "
                        + "VALUES (?, 'GITHUB', '42', NOW(6), NOW(6))", userId))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
