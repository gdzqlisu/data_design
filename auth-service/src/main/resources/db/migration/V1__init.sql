CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    display_name   VARCHAR(120) NULL,
    email          VARCHAR(255) NULL,
    avatar_url     VARCHAR(512) NULL,
    `role`         VARCHAR(32)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    is_break_glass TINYINT(1)   NOT NULL DEFAULT 0,
    password_hash  VARCHAR(100) NULL,
    token_version  INT          NOT NULL DEFAULT 0,
    approved_by    BIGINT       NULL,
    approved_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    last_login_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 邮箱故意不加唯一约束：GitHub 返回的邮箱未经验证，
    -- 两个不同身份共用同一邮箱是可能的，此时应该各建各的账号，
    -- 而不是把第二个人挡在门外或错误合并成同一个账号。
    KEY idx_users_email (email),
    KEY idx_users_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_identities (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(32)  NOT NULL,
    provider_user_id VARCHAR(128) NOT NULL,
    provider_login   VARCHAR(128) NULL,
    email            VARCHAR(255) NULL,
    avatar_url       VARCHAR(512) NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_provider_user (provider, provider_user_id),
    KEY idx_identity_user (user_id),
    CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    event       VARCHAR(40)  NOT NULL,
    provider    VARCHAR(32)  NULL,
    ip          VARCHAR(45)  NULL,
    user_agent  VARCHAR(512) NULL,
    detail_json TEXT         NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_user (user_id),
    KEY idx_audit_event_created (event, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
