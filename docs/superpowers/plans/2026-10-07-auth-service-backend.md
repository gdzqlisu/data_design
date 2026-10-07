# 后端认证服务实现计划（子项目 1 · 计划 A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `auth-service`：GitHub OAuth 登录、首登待审批、管理员审批与角色管理、本地破窗管理员、JWT 访问令牌与可吊销的刷新令牌，全部通过自动化测试验证。

**Architecture:** Spring Boot 3.3 单体服务，只对外提供 JSON API，不托管前端资源。认证状态由「短效 JWT（无状态）+ Redis 中的刷新令牌（有状态）」组成：JWT 让每次请求的校验廉价，Redis 让禁用与降权可以即时生效。GitHub 走 OAuth2 授权码流程，登录后按账号状态决定签发令牌还是跳转待审批。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Security 6、Spring Data JPA、Flyway、MySQL 8.0、Redis 7、jjwt 0.12.6、JUnit 5 + AssertJ + MockMvc + Testcontainers

**Spec:** `docs/superpowers/specs/2026-10-07-auth-and-console-shell-design.md`

**本计划范围：** 只有后端。前端控制台是计划 B，等本计划完成后另写。本计划结束时产物是一个可以用 curl 与自动化测试完整验证的 API。

---

## 环境前提

### Java 版本：17（偏离 spec 的 21）

spec 写的是 Java 21，本机实测不成立：

- `java -version` 是 `openjdk 17.0.19`（Homebrew）
- 本机只有 JDK 8 / 11 / 17 / 25，**没有 21**，brew 也没有 `openjdk@21`
- Spring Boot 3.3 的最低要求就是 Java 17，17 是当前 LTS，无需额外安装

如要改回 21：`brew install openjdk@21`，并把下面所有命令里的 JDK 路径换掉。

### Maven 必须绕过 `~/.mavenrc`

本机 `~/.mavenrc` 内容为 `export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)`，
它会**覆盖** shell 里的 `JAVA_HOME`，导致 `mvn` 始终跑在 Java 8 上。
Task 1 会生成 `auth-service/scripts/mvn` 固化绕过方式，**后续所有 Maven 命令一律用
`./scripts/mvn` 而不是 `mvn`**。

### 本机镜像

Testcontainers 与 docker-compose 只使用本机已有镜像，固定显式 tag，不拉取 `latest`：

| 用途 | 镜像 | 本机是否已有 |
|---|---|---|
| MySQL | `mysql:8.0` | 是 |
| Redis | `redis:7-alpine` | 是 |

---

## 文件结构

```
auth-service/
├─ scripts/mvn                                   Maven 包装脚本（绕过 ~/.mavenrc）
├─ pom.xml
└─ src/
   ├─ main/
   │  ├─ java/com/gdzqlisu/datadesign/auth/
   │  │  ├─ AuthServiceApplication.java
   │  │  ├─ common/       ApiException、ApiExceptionHandler（统一 JSON 错误体）
   │  │  ├─ config/       JwtProperties、JwtService、AccessTokenClaims、ClockConfig
   │  │  ├─ user/         User、UserIdentity、Role、UserStatus、AuthProvider、LoginOutcome
   │  │  │                UserProvisioningService + 两个仓储
   │  │  ├─ token/        RefreshTokenService、RefreshTokenRecord、IssuedRefreshToken
   │  │  │                RefreshCookieService、RefreshTokenConfig、两个异常
   │  │  ├─ security/     SecurityConfig、JwtAuthenticationFilter、AuthenticatedUser
   │  │  │                TokenVersionCache、LoginRateLimiter
   │  │  ├─ oauth/        GitHubOAuthService、GitHubProfile、OAuthProperties、AuthRequest
   │  │  │                OAuthStateStore、OAuthClientConfig、GitHubApiException
   │  │  ├─ admin/        AdminUserController、UserAdminService、请求/响应 DTO
   │  │  ├─ auth/         AuthController、MeController、LocalLoginController 与 DTO
   │  │  ├─ audit/        AuditService、AuditEvent、AuditLog、AuditLogRepository
   │  │  └─ bootstrap/    BreakGlassAdminInitializer、BreakGlassAdminProperties
   │  └─ resources/
   │     ├─ application.yml
   │     └─ db/migration/V1__init.sql
   └─ test/java/com/gdzqlisu/datadesign/auth/
      ├─ IntegrationTestBase.java                  Testcontainers 复用本机镜像
      ├─ AuthServiceApplicationTests.java
      ├─ DatabaseMigrationTest.java
      ├─ user/UserRepositoryTest.java
      ├─ user/UserProvisioningServiceTest.java
      ├─ config/JwtServiceTest.java
      ├─ token/RefreshTokenServiceTest.java
      ├─ security/JwtAuthenticationFilterTest.java
      ├─ audit/AuditServiceTest.java
      ├─ oauth/GitHubOAuthServiceTest.java
      ├─ oauth/OAuthStateStoreTest.java
      ├─ auth/AuthControllerTest.java
      ├─ bootstrap/BreakGlassAdminInitializerTest.java
      ├─ admin/AdminUserControllerTest.java
      └─ auth/LocalLoginTest.java

deploy/
└─ docker-compose.yml                            开发用 MySQL + Redis（nginx 走 edge profile）
```

每个文件一个明确职责：领域对象与仓储在 `user/`，令牌逻辑在 `token/`，请求鉴权在 `security/`；
控制器只做协议转换，业务规则放在 service 层。

---

### Task 1: 工程骨架与健康检查

**Files:**
- Create: `auth-service/scripts/mvn`
- Create: `auth-service/pom.xml`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/AuthServiceApplication.java`
- Create: `auth-service/src/main/resources/application.yml`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/AuthServiceApplicationTests.java`

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/AuthServiceApplicationTests.java`：

```java
package com.gdzqlisu.datadesign.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthServiceApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response =
                rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test
```

预期：失败，`./scripts/mvn: No such file or directory`（脚本还不存在）。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/scripts/mvn`：

```bash
#!/usr/bin/env bash
# 本机 ~/.mavenrc 把 JAVA_HOME 固定成 Java 8 并覆盖环境变量，
# 必须用 MAVEN_SKIP_RC 跳过它，否则 Spring Boot 3 无法编译。
set -euo pipefail
export MAVEN_SKIP_RC=1
export JAVA_HOME="${AUTH_SERVICE_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
exec mvn "$@"
```

创建 `auth-service/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>com.gdzqlisu.datadesign</groupId>
  <artifactId>auth-service</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>auth-service</name>
  <description>认证与账号体系</description>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/AuthServiceApplication.java`：

```java
package com.gdzqlisu.datadesign.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

创建 `auth-service/src/main/resources/application.yml`：

```yaml
server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
chmod +x auth-service/scripts/mvn
cd auth-service && ./scripts/mvn -q test
```

预期：`BUILD SUCCESS`，1 个测试通过。
若报 `invalid target release: 17`，说明 `~/.mavenrc` 绕过失败，
检查 `./scripts/mvn -v` 输出的 Java 版本应为 `17.0.19`。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 工程骨架与健康检查"
```

---

### Task 2: 本地依赖编排、Testcontainers 基座与初始建表

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `auth-service/src/main/resources/db/migration/V1__init.sql`
- Create: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/IntegrationTestBase.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/DatabaseMigrationTest.java`
- Modify: `auth-service/pom.xml`（加 JPA / Redis / Flyway / MySQL / Testcontainers）
- Modify: `auth-service/src/main/resources/application.yml`
- Modify: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/AuthServiceApplicationTests.java`（改为继承 `IntegrationTestBase`）

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/IntegrationTestBase.java`：

```java
package com.gdzqlisu.datadesign.auth;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 所有需要数据库或 Redis 的测试都继承本类。
 * 容器用静态字段持有，整个 JVM 只启动一次。
 * 镜像固定为本机已有版本，不联网拉取。
 *
 * 注意：这里用 @DynamicPropertySource 而不是 @ServiceConnection。
 * @ServiceConnection 只对 @TestConfiguration 里的 @Bean 方法生效，
 * 加在静态字段上不会注册连接，测试会去连真实的 localhost:3306。
 */
public abstract class IntegrationTestBase {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("data_design")
                    .withUsername("app")
                    .withPassword("app");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
```

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/DatabaseMigrationTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=DatabaseMigrationTest
```

预期：编译失败，`package org.testcontainers does not exist`，且 `V1__init.sql` 尚不存在。

- [ ] **Step 3: 写最小实现**

创建 `deploy/docker-compose.yml`：

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: data-design-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: data_design
      MYSQL_USER: app
      MYSQL_PASSWORD: app
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot"]
      interval: 5s
      timeout: 5s
      retries: 30

  redis:
    image: redis:7-alpine
    container_name: data-design-redis
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 30

  # 开发期不需要 nginx：Vite 代理已保证浏览器同源。
  # 生产/联调时先 docker pull nginx:1.27-alpine，再用 edge profile 启动。
  nginx:
    image: nginx:1.27-alpine
    container_name: data-design-nginx
    profiles: ["edge"]
    ports:
      - "80:80"

volumes:
  mysql-data:
```

创建 `auth-service/src/main/resources/db/migration/V1__init.sql`：

```sql
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
    UNIQUE KEY uk_users_email (email),
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
```

在 `auth-service/pom.xml` 的 `<properties>` 中追加：

```xml
    <testcontainers.version>1.20.3</testcontainers.version>
```

在 `auth-service/pom.xml` 的 `</dependencies>` 之前追加：

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <scope>test</scope>
    </dependency>
```

把 `auth-service/src/main/resources/application.yml` 覆盖为：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/data_design?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: app
    password: app
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  data:
    redis:
      host: localhost
      port: 6379

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

把 `AuthServiceApplicationTests` 的类声明改为继承基座：

```java
class AuthServiceApplicationTests extends IntegrationTestBase {
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test
```

预期：`BUILD SUCCESS`，3 个测试通过。首次运行会启动两个容器，约 30-60 秒。

若报找不到镜像，确认 `docker images` 里有 `mysql:8.0` 与 `redis:7-alpine`。
若 Testcontainers 仍尝试联网拉取，检查镜像名与本地 tag 是否完全一致（本地没有 `mysql:8.0.36`，
所以绝不能省掉显式 tag）。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service deploy && git commit -m "feat(auth-service): 本地依赖编排、Testcontainers 基座与初始建表"
```

---

### Task 3: 用户与身份领域模型

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/Role.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserStatus.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/AuthProvider.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/User.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserIdentity.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserRepository.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserIdentityRepository.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/user/UserRepositoryTest.java`

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/user/UserRepositoryTest.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Test
    void newUserStartsPendingWithMemberRole() {
        User saved = users.saveAndFlush(User.newPending("octocat", "octocat@example.com", null));

        List<User> pending = users.findAllByStatusOrderByCreatedAtAsc(UserStatus.PENDING);

        assertThat(pending).extracting(User::getId).contains(saved.getId());
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        assertThat(saved.getTokenVersion()).isZero();
        assertThat(saved.isBreakGlass()).isFalse();
    }

    @Test
    void approveSetsRoleStatusAndAuditFields() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));

        user.approve(Role.STRATEGIST, 99L);
        users.saveAndFlush(user);

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.getRole()).isEqualTo(Role.STRATEGIST);
        assertThat(reloaded.getApprovedBy()).isEqualTo(99L);
        assertThat(reloaded.getApprovedAt()).isNotNull();
    }

    @Test
    void findsUserByProviderIdentity() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));
        identities.saveAndFlush(UserIdentity.github(user, "583231", "octocat", null, null));

        Optional<User> found = users.findByProviderAndProviderUserId(AuthProvider.GITHUB, "583231");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
    }

    @Test
    void disableAndChangeRoleBumpTokenVersion() {
        User user = users.saveAndFlush(User.newPending("octocat", null, null));
        int before = user.getTokenVersion();

        user.changeRole(Role.VIEWER);
        users.saveAndFlush(user);

        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(before + 1);

        User reloaded = users.findById(user.getId()).orElseThrow();
        reloaded.disable();
        users.saveAndFlush(reloaded);

        User afterDisable = users.findById(user.getId()).orElseThrow();
        assertThat(afterDisable.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(afterDisable.getTokenVersion()).isEqualTo(before + 2);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=UserRepositoryTest
```

预期：编译失败，`cannot find symbol: class User`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/Role.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

/**
 * 本次只启用 ADMIN 与 MEMBER。STRATEGIST 与 VIEWER 是预留值，
 * 将来加角色只加枚举值，不改表结构。
 */
public enum Role {
    ADMIN,
    MEMBER,
    STRATEGIST,
    VIEWER
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserStatus.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

public enum UserStatus {
    PENDING,
    ACTIVE,
    DISABLED,
    REJECTED
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/AuthProvider.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

public enum AuthProvider {
    GITHUB,
    LOCAL
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/User.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name")
    private String displayName;

    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role = Role.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.PENDING;

    @Column(name = "is_break_glass", nullable = false)
    private boolean breakGlass;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    private User(String displayName, String email, String avatarUrl) {
        this.displayName = displayName;
        this.email = email;
        this.avatarUrl = avatarUrl;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User newPending(String displayName, String email, String avatarUrl) {
        return new User(displayName, email, avatarUrl);
    }

    public static User newBreakGlass(String displayName, String passwordHash) {
        User user = new User(displayName, null, null);
        user.breakGlass = true;
        user.passwordHash = passwordHash;
        user.role = Role.ADMIN;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public void approve(Role grantedRole, Long approverId) {
        this.role = grantedRole;
        this.status = UserStatus.ACTIVE;
        this.approvedBy = approverId;
        this.approvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void reject() {
        this.status = UserStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
        bumpTokenVersion();
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
        bumpTokenVersion();
    }

    public void bumpTokenVersion() {
        this.tokenVersion++;
        this.updatedAt = Instant.now();
    }

    public void recordLogin() {
        this.lastLoginAt = Instant.now();
        this.updatedAt = this.lastLoginAt;
    }

    public void refreshProfile(String newDisplayName, String newAvatarUrl, String newEmail) {
        if (newDisplayName != null) {
            this.displayName = newDisplayName;
        }
        if (newAvatarUrl != null) {
            this.avatarUrl = newAvatarUrl;
        }
        if (this.email == null) {
            this.email = newEmail;
        }
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isBreakGlass() {
        return breakGlass;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserIdentity.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "provider_login")
    private String providerLogin;

    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserIdentity() {
    }

    public static UserIdentity github(User user, String providerUserId, String providerLogin,
                                      String email, String avatarUrl) {
        UserIdentity identity = new UserIdentity();
        identity.userId = user.getId();
        identity.provider = AuthProvider.GITHUB;
        identity.providerUserId = providerUserId;
        identity.providerLogin = providerLogin;
        identity.email = email;
        identity.avatarUrl = avatarUrl;
        identity.touch();
        return identity;
    }

    public static UserIdentity local(User user) {
        UserIdentity identity = new UserIdentity();
        identity.userId = user.getId();
        identity.provider = AuthProvider.LOCAL;
        identity.providerUserId = String.valueOf(user.getId());
        identity.touch();
        return identity;
    }

    public void refreshProfile(String login, String newEmail, String newAvatarUrl) {
        if (login != null) {
            this.providerLogin = login;
        }
        if (newEmail != null) {
            this.email = newEmail;
        }
        if (newAvatarUrl != null) {
            this.avatarUrl = newAvatarUrl;
        }
        this.updatedAt = Instant.now();
    }

    private void touch() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getProviderLogin() {
        return providerLogin;
    }

    public String getEmail() {
        return email;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserRepository.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findAllByStatusOrderByCreatedAtAsc(UserStatus status);

    Optional<User> findByDisplayName(String displayName);

    @Query("""
           SELECT u FROM User u
           JOIN UserIdentity i ON i.userId = u.id
           WHERE i.provider = :provider AND i.providerUserId = :providerUserId
           """)
    Optional<User> findByProviderAndProviderUserId(@Param("provider") AuthProvider provider,
                                                   @Param("providerUserId") String providerUserId);
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserIdentityRepository.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    List<UserIdentity> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<UserIdentity> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=UserRepositoryTest
```

预期：`BUILD SUCCESS`，4 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 用户与身份领域模型"
```

---

### Task 4: JWT 签发与校验

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/JwtProperties.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/AccessTokenClaims.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/JwtService.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/ClockConfig.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/config/JwtServiceTest.java`
- Modify: `auth-service/pom.xml`（加 jjwt）
- Modify: `auth-service/src/main/resources/application.yml`（加 `auth.jwt`）

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/config/JwtServiceTest.java`：

```java
package com.gdzqlisu.datadesign.auth.config;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789-abcdef";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15), "data-design");

    @Test
    void issuesTokenCarryingUserIdRoleAndTokenVersion() {
        JwtService service = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        AccessTokenClaims claims = service.parse(service.issue(7L, "ADMIN", 3));

        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo("ADMIN");
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void rejectsExpiredToken() {
        JwtService issuing = new JwtService(properties, Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        String issuedAnHourAgo = issuing.issue(7L, "MEMBER", 0);
        JwtService now = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> now.parse(issuedAnHourAgo)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtService issuer = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        JwtService verifier = new JwtService(
                new JwtProperties("another-secret-0123456789-0123456789-abcdef", Duration.ofMinutes(15), "data-design"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.parse(issuer.issue(7L, "MEMBER", 0))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(15), "data-design"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=JwtServiceTest
```

预期：编译失败，`cannot find symbol: class JwtService`。

- [ ] **Step 3: 写最小实现**

在 `auth-service/pom.xml` 的 `</dependencies>` 之前追加：

```xml
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>0.12.6</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>0.12.6</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>0.12.6</version>
      <scope>runtime</scope>
    </dependency>
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/JwtProperties.java`：

```java
package com.gdzqlisu.datadesign.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, Duration accessTtl, String issuer) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "auth.jwt.secret 至少需要 32 字节，推荐用 openssl rand -base64 48 生成");
        }
        Objects.requireNonNull(accessTtl, "auth.jwt.access-ttl 未配置");
        Objects.requireNonNull(issuer, "auth.jwt.issuer 未配置");
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/AccessTokenClaims.java`：

```java
package com.gdzqlisu.datadesign.auth.config;

import java.time.Instant;

public record AccessTokenClaims(Long userId, String role, int tokenVersion, String jti, Instant expiresAt) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/JwtService.java`：

```java
package com.gdzqlisu.datadesign.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtService {

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(long userId, String role, int tokenVersion) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .claim("role", role)
                .claim("tv", tokenVersion)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(key)
                .compact();
    }

    public AccessTokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new AccessTokenClaims(
                Long.parseLong(claims.getSubject()),
                claims.get("role", String.class),
                claims.get("tv", Integer.class),
                claims.getId(),
                claims.getExpiration().toInstant());
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/config/ClockConfig.java`：

```java
package com.gdzqlisu.datadesign.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * 统一注入 Clock，测试里换成固定时钟即可验证过期逻辑。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

在 `auth-service/src/main/resources/application.yml` 的 `management:` 之前插入：

```yaml
auth:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-0123456789-0123456789-abcdef}
    access-ttl: 15m
    issuer: data-design
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=JwtServiceTest
```

预期：`BUILD SUCCESS`，4 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): JWT 签发与校验"
```

---

### Task 5: 刷新令牌服务（Redis 存储、轮换、复用检测）

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenRecord.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/IssuedRefreshToken.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/InvalidRefreshTokenException.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenReuseException.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenService.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenConfig.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenServiceTest.java`
- Modify: `auth-service/src/main/resources/application.yml`（加 `auth.refresh-ttl`）

**设计要点：** Cookie 里放的是 `{jti}.{secret}`，Redis 里只存 secret 的 SHA-256，避免 Redis 泄露即等价于令牌泄露。`rt:{jti}` 存记录，`rtu:{userId}` 是一个 Set，用于一次性撤销该用户整条刷新链（复用检测触发时要用）。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenServiceTest.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RefreshTokenServiceTest extends IntegrationTestBase {

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper mapper;

    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
    }

    @Test
    void issuedTokenCanBeRotatedOnce() {
        long userId = randomUserId();
        IssuedRefreshToken first = service.issue(userId, 0, "JUnit");

        IssuedRefreshToken second = service.rotate(first.rawToken(), "JUnit");

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(second.jti()).isNotEqualTo(first.jti());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeChain() {
        long userId = randomUserId();
        IssuedRefreshToken first = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken second = service.rotate(first.rawToken(), "JUnit");

        assertThatThrownBy(() -> service.rotate(first.rawToken(), "JUnit"))
                .isInstanceOf(RefreshTokenReuseException.class);

        assertThatThrownBy(() -> service.rotate(second.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokeAllForUserInvalidatesEveryTokenOfThatUser() {
        long userId = randomUserId();
        IssuedRefreshToken a = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken b = service.issue(userId, 0, "JUnit");

        service.revokeAllForUser(userId);

        assertThatThrownBy(() -> service.rotate(a.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotate(b.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("11111111-1111-1111-1111-111111111111.deadbeef", "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("not-a-token", "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        RefreshTokenService shortLived = new RefreshTokenService(redis, mapper, Duration.ofMillis(80));
        long userId = randomUserId();
        IssuedRefreshToken token = shortLived.issue(userId, 0, "JUnit");

        Thread.sleep(200);

        assertThatThrownBy(() -> shortLived.rotate(token.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokeRemovesOnlyTheGivenToken() {
        long userId = randomUserId();
        IssuedRefreshToken a = service.issue(userId, 0, "JUnit");
        IssuedRefreshToken b = service.issue(userId, 0, "JUnit");

        service.revoke(a.rawToken());

        assertThatThrownBy(() -> service.rotate(a.rawToken(), "JUnit"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(service.rotate(b.rawToken(), "JUnit").rawToken()).isNotBlank();
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=RefreshTokenServiceTest
```

预期：编译失败，`cannot find symbol: class RefreshTokenService`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenRecord.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshTokenRecord(
        String jti,
        String secretHash,
        long userId,
        int tokenVersion,
        String userAgentHash,
        Instant createdAt,
        Instant expiresAt,
        String rotatedTo) {

    public RefreshTokenRecord withRotatedTo(String newJti) {
        return new RefreshTokenRecord(jti, secretHash, userId, tokenVersion, userAgentHash,
                createdAt, expiresAt, newJti);
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/IssuedRefreshToken.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import java.time.Instant;

/**
 * rawToken 是写进 Cookie 的值，形如 {jti}.{secret}，只在签发时可见一次。
 */
public record IssuedRefreshToken(String rawToken, String jti, Instant expiresAt) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/InvalidRefreshTokenException.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenReuseException.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

/**
 * 已轮换过的令牌被再次使用，按令牌被盗处理：调用方应撤销该用户整条刷新链并记审计。
 */
public class RefreshTokenReuseException extends RuntimeException {

    private final long userId;

    public RefreshTokenReuseException(long userId) {
        super("刷新令牌被重复使用，已撤销该用户的全部刷新令牌");
        this.userId = userId;
    }

    public long getUserId() {
        return userId;
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenService.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

public class RefreshTokenService {

    private static final String RECORD_PREFIX = "rt:";
    private static final String USER_SET_PREFIX = "rtu:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public IssuedRefreshToken issue(long userId, int tokenVersion, String userAgent) {
        String jti = UUID.randomUUID().toString();
        String secret = randomSecret();
        Instant now = Instant.now();
        RefreshTokenRecord record = new RefreshTokenRecord(
                jti, sha256(secret), userId, tokenVersion, sha256(userAgent == null ? "" : userAgent),
                now, now.plus(ttl), null);
        save(record);
        String userKey = USER_SET_PREFIX + userId;
        redis.opsForSet().add(userKey, jti);
        redis.expire(userKey, ttl);
        return new IssuedRefreshToken(jti + "." + secret, jti, record.expiresAt());
    }

    public IssuedRefreshToken rotate(String rawToken, String userAgent) {
        String[] parts = split(rawToken);
        String jti = parts[0];
        String secret = parts[1];

        RefreshTokenRecord existing = load(jti);
        if (existing == null) {
            throw new InvalidRefreshTokenException("刷新令牌不存在、已过期或已被撤销");
        }
        if (!MessageDigest.isEqual(
                existing.secretHash().getBytes(StandardCharsets.UTF_8),
                sha256(secret).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidRefreshTokenException("刷新令牌校验失败");
        }
        if (existing.rotatedTo() != null) {
            revokeAllForUser(existing.userId());
            throw new RefreshTokenReuseException(existing.userId());
        }
        if (existing.expiresAt().isBefore(Instant.now())) {
            delete(jti);
            throw new InvalidRefreshTokenException("刷新令牌已过期");
        }

        IssuedRefreshToken next = issue(existing.userId(), existing.tokenVersion(), userAgent);
        save(existing.withRotatedTo(next.jti()));
        return next;
    }

    public void revoke(String rawToken) {
        String[] parts;
        try {
            parts = split(rawToken);
        } catch (InvalidRefreshTokenException ignored) {
            return;
        }
        RefreshTokenRecord record = load(parts[0]);
        delete(parts[0]);
        if (record != null) {
            redis.opsForSet().remove(USER_SET_PREFIX + record.userId(), parts[0]);
        }
    }

    public void revokeAllForUser(long userId) {
        Set<String> jtis = redis.opsForSet().members(USER_SET_PREFIX + userId);
        if (jtis != null && !jtis.isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (String jti : jtis) {
                keys.add(RECORD_PREFIX + jti);
            }
            redis.delete(keys);
        }
        redis.delete(USER_SET_PREFIX + userId);
    }

    private String[] split(String rawToken) {
        if (rawToken == null) {
            throw new InvalidRefreshTokenException("刷新令牌为空");
        }
        int dot = rawToken.indexOf('.');
        if (dot <= 0 || dot == rawToken.length() - 1) {
            throw new InvalidRefreshTokenException("刷新令牌格式不正确");
        }
        return new String[]{rawToken.substring(0, dot), rawToken.substring(dot + 1)};
    }

    private RefreshTokenRecord load(String jti) {
        String json = redis.opsForValue().get(RECORD_PREFIX + jti);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, RefreshTokenRecord.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRefreshTokenException("刷新令牌记录无法解析");
        }
    }

    private void save(RefreshTokenRecord record) {
        try {
            redis.opsForValue().set(RECORD_PREFIX + record.jti(), mapper.writeValueAsString(record), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("刷新令牌记录无法序列化", e);
        }
    }

    private void delete(String jti) {
        redis.delete(RECORD_PREFIX + jti);
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenConfig.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RefreshTokenConfig {

    @Bean
    public RefreshTokenService refreshTokenService(StringRedisTemplate redis,
                                                   ObjectMapper mapper,
                                                   @Value("${auth.refresh-ttl}") Duration ttl) {
        return new RefreshTokenService(redis, mapper, ttl);
    }
}
```

在 `auth-service/src/main/resources/application.yml` 的 `auth.jwt` 同级加上：

```yaml
auth:
  refresh-ttl: 7d
```

（与已有的 `auth.jwt` 合并到同一个 `auth:` 块下，不要写成两个 `auth:` 顶层键。）

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=RefreshTokenServiceTest
```

预期：`BUILD SUCCESS`，7 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 刷新令牌服务，支持轮换与复用检测"
```

---

### Task 6: 安全配置与请求鉴权过滤器

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/AuthenticatedUser.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/TokenVersionCache.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/JwtAuthenticationFilter.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/SecurityConfig.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/MeController.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/security/JwtAuthenticationFilterTest.java`
- Modify: `auth-service/pom.xml`（加 `spring-boot-starter-security` 与 `spring-security-test`）

**设计要点：** 过滤器**不要**声明为 `@Component`，否则 Spring Boot 会把它额外注册到 servlet 容器，
导致它执行两次。由 `SecurityConfig` 直接 `new` 出来只挂在安全链上。

**关于 CSRF：** 浏览器只访问 Vite/Nginx 那个 origin，API 走同源代理，且不依赖 Cookie 做鉴权
（access token 在 `Authorization` 头里），因此关闭 CSRF 是安全的。计划 B 里 refresh token 走
Cookie，刷新接口会额外校验 `Origin`，届时在 Task 10 一并处理。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/security/JwtAuthenticationFilterTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=JwtAuthenticationFilterTest
```

预期：编译失败，`cannot find symbol: class TokenVersionCache`。

- [ ] **Step 3: 写最小实现**

在 `auth-service/pom.xml` 的 `</dependencies>` 之前追加：

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/AuthenticatedUser.java`：

```java
package com.gdzqlisu.datadesign.auth.security;

public record AuthenticatedUser(Long id, String displayName, String role, int tokenVersion) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/TokenVersionCache.java`：

```java
package com.gdzqlisu.datadesign.auth.security;

import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存 users.token_version，避免每个请求都打数据库。
 * 改角色与禁用账号时必须调用 evict，让生效接近即时；60 秒 TTL 只是兜底。
 */
@Component
public class TokenVersionCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "tv:";

    private final StringRedisTemplate redis;
    private final UserRepository users;

    public TokenVersionCache(StringRedisTemplate redis, UserRepository users) {
        this.redis = redis;
        this.users = users;
    }

    public int currentTokenVersion(long userId) {
        String key = PREFIX + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return Integer.parseInt(cached);
        }
        int actual = users.findById(userId).map(User::getTokenVersion).orElse(-1);
        redis.opsForValue().set(key, Integer.toString(actual), TTL);
        return actual;
    }

    public void evict(long userId) {
        redis.delete(PREFIX + userId);
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/JwtAuthenticationFilter.java`：

```java
package com.gdzqlisu.datadesign.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.config.AccessTokenClaims;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository users;
    private final TokenVersionCache tokenVersions;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users,
                                   TokenVersionCache tokenVersions, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.users = users;
        this.tokenVersions = tokenVersions;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        AccessTokenClaims claims;
        try {
            claims = jwtService.parse(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", "访问令牌无效或已过期");
            return;
        }

        if (tokenVersions.currentTokenVersion(claims.userId()) != claims.tokenVersion()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "token_revoked", "访问令牌已被撤销，请重新登录");
            return;
        }

        User user = users.findById(claims.userId()).orElse(null);
        if (user == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "user_not_found", "账号不存在");
            return;
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "account_not_active",
                    "账号当前状态为 " + user.getStatus() + "，无法访问");
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(), user.getDisplayName(), user.getRole().name(), user.getTokenVersion());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/SecurityConfig.java`：

```java
package com.gdzqlisu.datadesign.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              JwtService jwtService,
                                              UserRepository users,
                                              TokenVersionCache tokenVersions,
                                              ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint(mapper))
                        .accessDeniedHandler(accessDeniedHandler(mapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, users, tokenVersions, mapper),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(),
                    Map.of("code", "unauthenticated", "message", "请先登录"));
        };
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(),
                    Map.of("code", "forbidden", "message", "没有访问该资源的权限"));
        };
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/MeController.java`：

```java
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
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=JwtAuthenticationFilterTest
```

预期：`BUILD SUCCESS`，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 请求鉴权过滤器与安全配置

过滤器校验签名、token_version 与账号状态，三者任一不通过即拒绝。
token_version 走 Redis 缓存，改角色或禁用账号时主动 evict。"
```

---

### Task 7: 审计服务

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditEvent.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditLog.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditLogRepository.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditService.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/audit/AuditServiceTest.java`

**设计要点：** `audit_logs.user_id` 故意不加外键，账号被删也要留下审计痕迹。写入审计失败不能影响主流程，
`record` 内部捕获异常只记日志——审计丢一条可以接受，登录被审计拖挂不行。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/audit/AuditServiceTest.java`：

```java
package com.gdzqlisu.datadesign.auth.audit;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditServiceTest extends IntegrationTestBase {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository logs;

    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    }

    @Test
    void recordsEventWithAllFields() {
        long userId = randomUserId();

        audit.record(userId, AuditEvent.LOGIN_PENDING, "GITHUB", "203.0.113.7", "JUnit-Agent",
                Map.of("login", "octocat"));

        AuditLog saved = logs.findAllByUserIdOrderByCreatedAtDesc(userId).get(0);
        assertThat(saved.getEvent()).isEqualTo(AuditEvent.LOGIN_PENDING);
        assertThat(saved.getProvider()).isEqualTo("GITHUB");
        assertThat(saved.getIp()).isEqualTo("203.0.113.7");
        assertThat(saved.getDetailJson()).contains("octocat");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void nullDetailIsPersistedAsNull() {
        long userId = randomUserId();

        audit.record(userId, AuditEvent.LOGOUT, null, null, null, null);

        AuditLog saved = logs.findAllByUserIdOrderByCreatedAtDesc(userId).get(0);
        assertThat(saved.getDetailJson()).isNull();
        assertThat(saved.getEvent()).isEqualTo(AuditEvent.LOGOUT);
    }

    @Test
    void readsClientIpFromForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("10.0.0.1");

        assertThat(AuditService.clientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");

        assertThat(AuditService.clientIp(request)).isEqualTo("10.0.0.9");
    }

    @Test
    void truncatesOverlongUserAgent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "x".repeat(900));

        assertThat(AuditService.userAgent(request)).hasSize(512);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=AuditServiceTest
```

预期：编译失败，`cannot find symbol: class AuditService`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditEvent.java`：

```java
package com.gdzqlisu.datadesign.auth.audit;

public enum AuditEvent {
    LOGIN_SUCCESS,
    LOGIN_PENDING,
    LOGIN_REJECTED,
    LOGIN_DISABLED,
    LOGIN_FAILED,
    APPROVED,
    REJECTED,
    ROLE_CHANGED,
    USER_DISABLED,
    LOGOUT,
    TOKEN_REVOKED,
    BREAK_GLASS_LOGIN
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditLog.java`：

```java
package com.gdzqlisu.datadesign.auth.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditEvent event;

    private String provider;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "detail_json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    static AuditLog of(Long userId, AuditEvent event, String provider, String ip,
                       String userAgent, String detailJson, Instant createdAt) {
        AuditLog log = new AuditLog();
        log.userId = userId;
        log.event = event;
        log.provider = provider;
        log.ip = ip;
        log.userAgent = userAgent;
        log.detailJson = detailJson;
        log.createdAt = createdAt;
        return log;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AuditEvent getEvent() {
        return event;
    }

    public String getProvider() {
        return provider;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditLogRepository.java`：

```java
package com.gdzqlisu.datadesign.auth.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditLog> findAllByEventOrderByCreatedAtDesc(AuditEvent event);
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/audit/AuditService.java`：

```java
package com.gdzqlisu.datadesign.auth.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int USER_AGENT_MAX = 512;

    private final AuditLogRepository logs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate auditTx;

    public AuditService(AuditLogRepository logs, ObjectMapper mapper, Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.mapper = mapper;
        this.clock = clock;
        this.auditTx = new TransactionTemplate(transactionManager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 审计写入失败不影响主流程：登录不能因为记不上审计而失败。
     * 这里必须用编程式事务。写成 @Transactional(REQUIRES_NEW) 有两个坑：
     * 一是 recordFromRequest 内部调用 record 属于自调用，注解被代理绕过，
     * 事务会落回调用方（那条"失败不影响主流程"就名存实亡）；
     * 二是声明式事务的提交点在方法之外，提交阶段的异常 catch 不到。
     */
    public void record(Long userId, AuditEvent event, String provider,
                       String ip, String userAgent, Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            log.warn("审计明细序列化失败 event={} userId={}", event, userId, e);
            return;
        }
        try {
            auditTx.executeWithoutResult(status -> logs.save(
                    AuditLog.of(userId, event, provider, ip, userAgent, detailJson, clock.instant())));
        } catch (RuntimeException e) {
            log.warn("审计写入失败 event={} userId={}", event, userId, e);
        }
    }

    public void recordFromRequest(Long userId, AuditEvent event, String provider,
                                  HttpServletRequest request, Map<String, Object> detail) {
        record(userId, event, provider, clientIp(request), userAgent(request), detail);
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=AuditServiceTest
```

预期：`BUILD SUCCESS`，5 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 审计服务与 12 种认证事件"
```

---

### Task 8: GitHub OAuth 客户端与授权请求暂存

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthProperties.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/AuthRequest.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthStateStore.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubProfile.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubApiException.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubOAuthService.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthClientConfig.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/oauth/OAuthStateStoreTest.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/oauth/GitHubOAuthServiceTest.java`
- Modify: `auth-service/src/main/resources/application.yml`（加 `auth.oauth`）

**设计要点：** 不用 Spring Security 的 `oauth2Login` 过滤器链，改成显式三步
（`authorize` 建跳转 → 回调校验 state → 换取 profile）。原因是我们要在登录成功后有完全自定义的
分支行为（待审批/被拒/禁用各走不同跳转，并自行签发令牌），显式实现比定制过滤器链更容易测试和理解。

**state 与 PKCE：** `state` 是一次性随机串，和 `code_verifier` 一起存 Redis 10 分钟，用 `getAndDelete`
取出即删，天然防重放；PKCE 防授权码被截获后直接兑换。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/oauth/OAuthStateStoreTest.java`：

```java
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
```

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/oauth/GitHubOAuthServiceTest.java`：

```java
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
        assertThat(url).contains("scope=read:user%20user%3Aemail");
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='OAuthStateStoreTest,GitHubOAuthServiceTest'
```

预期：编译失败，`cannot find symbol: class OAuthStateStore`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthProperties.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.oauth")
public record OAuthProperties(GitHub github, String consoleBaseUrl) {

    public record GitHub(String clientId, String clientSecret, String authorizeUrl,
                         String tokenUrl, String apiBaseUrl, String redirectUri) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/AuthRequest.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthRequest(String codeVerifier) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthStateStore.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 暂存 OAuth2 授权请求。consume 用 getAndDelete，取出即删，天然防重放。
 */
@Component
public class OAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "oauth2:authreq:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OAuthStateStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void save(String state, AuthRequest request) {
        try {
            redis.opsForValue().set(PREFIX + state, mapper.writeValueAsString(request), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("授权请求无法序列化", e);
        }
    }

    public AuthRequest consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().getAndDelete(PREFIX + state);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, AuthRequest.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubProfile.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

/**
 * 从 GitHub 拉回来的原始信息，已归一化：邮箱统一小写，displayName 一定非空。
 */
public record GitHubProfile(String providerUserId, String login, String displayName,
                            String email, String avatarUrl) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubApiException.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

public class GitHubApiException extends RuntimeException {

    public GitHubApiException(String message) {
        super(message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/GitHubOAuthService.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

public class GitHubOAuthService {

    private static final String SCOPE = "read:user user:email";

    private final RestClient http;
    private final OAuthProperties.GitHub github;

    public GitHubOAuthService(RestClient.Builder builder, OAuthProperties properties) {
        this.github = properties.github();
        this.http = builder.build();
    }

    public boolean configured() {
        return github.configured();
    }

    public String authorizeUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(github.authorizeUrl())
                .queryParam("client_id", github.clientId())
                .queryParam("redirect_uri", github.redirectUri())
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .toUriString();
    }

    public GitHubProfile exchange(String code, String codeVerifier) {
        String accessToken = requestAccessToken(code, codeVerifier);
        GitHubUserResponse user = fetchUser(accessToken);

        String email = user.email();
        if (email == null || email.isBlank()) {
            email = fetchPrimaryEmail(accessToken);
        }
        String displayName = user.name() != null && !user.name().isBlank() ? user.name() : user.login();

        return new GitHubProfile(
                String.valueOf(user.id()),
                user.login(),
                displayName,
                email == null ? null : email.trim().toLowerCase(),
                user.avatarUrl());
    }

    private String requestAccessToken(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", github.clientId());
        form.add("client_secret", github.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", github.redirectUri());
        form.add("code_verifier", codeVerifier);

        AccessTokenResponse response;
        try {
            response = http.post()
                    .uri(github.tokenUrl())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(AccessTokenResponse.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("与 GitHub 交换令牌失败", e);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new GitHubApiException("GitHub 未返回访问令牌，授权码可能已失效或已被使用");
        }
        return response.accessToken();
    }

    private GitHubUserResponse fetchUser(String accessToken) {
        GitHubUserResponse user;
        try {
            user = http.get()
                    .uri(github.apiBaseUrl() + "/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubUserResponse.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("读取 GitHub 用户信息失败", e);
        }
        if (user == null || user.id() == null) {
            throw new GitHubApiException("GitHub 未返回用户标识");
        }
        return user;
    }

    private String fetchPrimaryEmail(String accessToken) {
        GitHubEmailResponse[] emails;
        try {
            emails = http.get()
                    .uri(github.apiBaseUrl() + "/user/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubEmailResponse[].class);
        } catch (RestClientException e) {
            throw new GitHubApiException("读取 GitHub 邮箱失败", e);
        }
        if (emails == null) {
            return null;
        }
        for (GitHubEmailResponse candidate : emails) {
            if (candidate.primary() && candidate.verified()) {
                return candidate.email();
            }
        }
        for (GitHubEmailResponse candidate : emails) {
            if (candidate.verified()) {
                return candidate.email();
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessTokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubUserResponse(Long id, String login, String name,
                                     @JsonProperty("avatar_url") String avatarUrl,
                                     String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubEmailResponse(String email, boolean primary, boolean verified) {
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/oauth/OAuthClientConfig.java`：

```java
package com.gdzqlisu.datadesign.auth.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OAuthClientConfig {

    @Bean
    public GitHubOAuthService gitHubOAuthService(RestClient.Builder builder, OAuthProperties properties) {
        return new GitHubOAuthService(builder, properties);
    }
}
```

在 `auth-service/src/main/resources/application.yml` 的 `auth:` 块下追加（与 `jwt`、`refresh-ttl` 同级）：

```yaml
  oauth:
    console-base-url: ${CONSOLE_BASE_URL:http://localhost:5173}
    github:
      client-id: ${GITHUB_CLIENT_ID:}
      client-secret: ${GITHUB_CLIENT_SECRET:}
      authorize-url: https://github.com/login/oauth/authorize
      token-url: https://github.com/login/oauth/access_token
      api-base-url: ${GITHUB_API_BASE_URL:https://api.github.com}
      redirect-uri: ${GITHUB_REDIRECT_URI:http://localhost:5173/api/auth/github/callback}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='OAuthStateStoreTest,GitHubOAuthServiceTest'
```

预期：`BUILD SUCCESS`，5 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): GitHub OAuth 客户端与授权请求暂存"
```

---

### Task 9: 用户开通状态机

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/LoginOutcome.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserProvisioningService.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/user/UserProvisioningServiceTest.java`

**设计要点：** 这是「GitHub 登录之后会发生什么」的唯一真相来源。控制器只负责把它翻译成跳转和 Cookie。
身份查不到就建 `PENDING`，查得到就按既有 `status` 决定审计事件——**绝不因为「邮箱相同」把两个身份合并**，
GitHub 邮箱未经企业域校验，自动合并等于开了一个越权入口。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/user/UserProvisioningServiceTest.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditLogRepository;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserProvisioningServiceTest extends IntegrationTestBase {

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Autowired
    private AuditLogRepository auditLogs;

    private static final MockHttpServletRequest REQUEST = new MockHttpServletRequest();

    private GitHubProfile profile(String id, String login, String email) {
        return new GitHubProfile(id, login, "The " + login, email, "https://avatars.example/" + login + ".png");
    }

    private String uniqueId() {
        return Long.toString(System.nanoTime());
    }

    @Test
    void firstLoginCreatesPendingUserWithGithubIdentity() {
        String githubId = uniqueId();

        LoginOutcome outcome = provisioning.login(profile(githubId, "octocat", "octocat@github.com"), REQUEST);

        assertThat(outcome.newlyCreated()).isTrue();
        assertThat(outcome.user().getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(outcome.user().getRole()).isEqualTo(Role.MEMBER);
        assertThat(identities.findByProviderAndProviderUserId(AuthProvider.GITHUB, githubId)).isPresent();
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(outcome.user().getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_PENDING);
    }

    @Test
    void secondLoginReusesTheSameUserAndDoesNotDuplicateIdentity() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat-renamed", null), REQUEST);

        assertThat(second.newlyCreated()).isFalse();
        assertThat(second.user().getId()).isEqualTo(first.user().getId());
        assertThat(identities.findAllByUserIdOrderByCreatedAtAsc(first.user().getId())).hasSize(1);
    }

    @Test
    void approvedUserLoginIsAuditedAsSuccess() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);
        User user = users.findById(first.user().getId()).orElseThrow();
        user.approve(Role.MEMBER, null);
        users.saveAndFlush(user);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        assertThat(second.user().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_SUCCESS);
    }

    @Test
    void disabledUserLoginIsAuditedAsDisabled() {
        String githubId = uniqueId();
        LoginOutcome first = provisioning.login(profile(githubId, "octocat", null), REQUEST);
        User user = users.findById(first.user().getId()).orElseThrow();
        user.approve(Role.MEMBER, null);
        user.disable();
        users.saveAndFlush(user);

        LoginOutcome second = provisioning.login(profile(githubId, "octocat", null), REQUEST);

        assertThat(second.user().getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(auditLogs.findAllByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(log -> log.getEvent())
                .contains(AuditEvent.LOGIN_DISABLED);
    }

    @Test
    void sameEmailFromDifferentGithubAccountsIsNotMerged() {
        String email = "shared-" + System.nanoTime() + "@example.com";
        LoginOutcome first = provisioning.login(profile(uniqueId(), "alice", email), REQUEST);
        LoginOutcome second = provisioning.login(profile(uniqueId(), "bob", email), REQUEST);

        assertThat(second.user().getId()).isNotEqualTo(first.user().getId());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=UserProvisioningServiceTest
```

预期：编译失败，`cannot find symbol: class UserProvisioningService`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/LoginOutcome.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

public record LoginOutcome(User user, boolean newlyCreated) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/UserProvisioningService.java`：

```java
package com.gdzqlisu.datadesign.auth.user;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class UserProvisioningService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final AuditService audit;

    public UserProvisioningService(UserRepository users, UserIdentityRepository identities, AuditService audit) {
        this.users = users;
        this.identities = identities;
        this.audit = audit;
    }

    @Transactional
    public LoginOutcome login(GitHubProfile profile, HttpServletRequest request) {
        Optional<User> existing =
                users.findByProviderAndProviderUserId(AuthProvider.GITHUB, profile.providerUserId());

        if (existing.isEmpty()) {
            User created = users.saveAndFlush(
                    User.newPending(profile.displayName(), profile.email(), profile.avatarUrl()));
            identities.saveAndFlush(UserIdentity.github(created, profile.providerUserId(),
                    profile.login(), profile.email(), profile.avatarUrl()));
            audit.recordFromRequest(created.getId(), AuditEvent.LOGIN_PENDING, "GITHUB", request,
                    Map.of("login", profile.login()));
            return new LoginOutcome(created, true);
        }

        User user = existing.get();
        user.refreshProfile(profile.displayName(), profile.avatarUrl(), profile.email());
        user.recordLogin();
        users.saveAndFlush(user);
        identities.findByProviderAndProviderUserId(AuthProvider.GITHUB, profile.providerUserId())
                .ifPresent(identity -> {
                    identity.refreshProfile(profile.login(), profile.email(), profile.avatarUrl());
                    identities.saveAndFlush(identity);
                });
        audit.recordFromRequest(user.getId(), auditEventFor(user.getStatus()), "GITHUB", request,
                Map.of("login", profile.login()));
        return new LoginOutcome(user, false);
    }

    private static AuditEvent auditEventFor(UserStatus status) {
        return switch (status) {
            case ACTIVE -> AuditEvent.LOGIN_SUCCESS;
            case PENDING -> AuditEvent.LOGIN_PENDING;
            case REJECTED -> AuditEvent.LOGIN_REJECTED;
            case DISABLED -> AuditEvent.LOGIN_DISABLED;
        };
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=UserProvisioningServiceTest
```

预期：`BUILD SUCCESS`，5 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 用户开通状态机，首登建待审批账号"
```

---

### Task 10: 认证控制器与令牌 Cookie

**Files:**
- Modify: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenService.java`（抽出 `inspect`，只读校验不轮换）
- Modify: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/token/RefreshTokenServiceTest.java`（补 `inspect` 的测试）
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshCookieService.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/common/ApiException.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/common/ApiExceptionHandler.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/SessionResponse.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/TokenResponse.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/AuthController.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/auth/AuthControllerTest.java`

**设计要点：** 浏览器侧令牌分两处——access token 在前端内存里（每条请求带 `Authorization` 头），
refresh token 在 httpOnly Cookie 里（`ds_rt`，`SameSite=Lax`，生产环境加 `Secure`）。
`GET /api/auth/session` 用 `inspect` 只读校验，**不能**用 `rotate`：两个标签页同时打开会互相轮换，
把对方的令牌判成「复用」而误杀整条链。

错误响应统一走本任务新建的 `common.ApiException` + `ApiExceptionHandler`，输出
`{"code": "...", "message": "..."}`。Task 6 里承诺的「刷新接口校验 `Origin`」也在本任务落地：
`POST /api/auth/refresh` 若带了 `Origin` 且与 `auth.oauth.console-base-url` 不同源，直接 403
`cross_origin`——这是 Cookie 鉴权下廉价且有效的 CSRF 兜底。

- [ ] **Step 1: 先写失败的测试**

在 `RefreshTokenServiceTest` 中追加：

```java
    @Test
    void inspectValidatesWithoutRotating() {
        long userId = randomUserId();
        IssuedRefreshToken token = service.issue(userId, 0, "JUnit");

        RefreshTokenRecord first = service.inspect(token.rawToken());
        RefreshTokenRecord second = service.inspect(token.rawToken());

        assertThat(first.userId()).isEqualTo(userId);
        assertThat(second.jti()).isEqualTo(first.jti());
        assertThat(service.rotate(token.rawToken(), "JUnit").rawToken()).isNotBlank();
    }
```

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/auth/AuthControllerTest.java`：

```java
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

@SpringBootTest
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='RefreshTokenServiceTest,AuthControllerTest'
```

预期：编译失败，`cannot find symbol: method inspect`。

- [ ] **Step 3: 写最小实现**

把 `RefreshTokenService` 的 `rotate` 改为复用新抽出的 `inspect`，并在类中插入：

```java
    /**
     * 只校验不轮换，供 /api/auth/session 这类只读查询使用。
     * 轮换式校验会让并发的两个标签页互相把对方判成盗用。
     */
    public RefreshTokenRecord inspect(String rawToken) {
        String[] parts = split(rawToken);
        RefreshTokenRecord record = load(parts[0]);
        if (record == null) {
            throw new InvalidRefreshTokenException("刷新令牌不存在、已过期或已被撤销");
        }
        if (!MessageDigest.isEqual(
                record.secretHash().getBytes(StandardCharsets.UTF_8),
                sha256(parts[1]).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidRefreshTokenException("刷新令牌校验失败");
        }
        if (record.rotatedTo() != null) {
            revokeAllForUser(record.userId());
            throw new RefreshTokenReuseException(record.userId());
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            delete(parts[0]);
            throw new InvalidRefreshTokenException("刷新令牌已过期");
        }
        return record;
    }
```

同时把 `rotate` 方法体替换为：

```java
    public IssuedRefreshToken rotate(String rawToken, String userAgent) {
        RefreshTokenRecord existing = inspect(rawToken);
        IssuedRefreshToken next = issue(existing.userId(), existing.tokenVersion(), userAgent);
        save(existing.withRotatedTo(next.jti()));
        return next;
    }
```

先建立统一的 API 错误契约。Task 11、Task 12 会直接复用这两个类，所以放在这里一次做掉：
测试断言的是 `{"code": "...", "message": "..."}`，而 `ResponseStatusException` 走 Spring 的
ProblemDetail 结构（字段是 `detail`/`status`），对不上，因此所有业务错误一律走 `ApiException`。

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/common/ApiException.java`：

```java
package com.gdzqlisu.datadesign.auth.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/common/ApiExceptionHandler.java`：

```java
package com.gdzqlisu.datadesign.auth.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/token/RefreshCookieService.java`：

```java
package com.gdzqlisu.datadesign.auth.token;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Component
public class RefreshCookieService {

    public static final String COOKIE_NAME = "ds_rt";
    private static final String COOKIE_PATH = "/api/auth";

    private final Duration ttl;
    private final boolean secure;

    public RefreshCookieService(@Value("${auth.refresh-ttl}") Duration ttl,
                                @Value("${auth.cookie-secure:false}") boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    public void write(HttpServletResponse response, IssuedRefreshToken token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(token.rawToken(), ttl.toSeconds()));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", 0));
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private String build(String value, long maxAgeSeconds) {
        StringBuilder builder = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(value)
                .append("; Path=").append(COOKIE_PATH)
                .append("; Max-Age=").append(maxAgeSeconds)
                .append("; HttpOnly")
                .append("; SameSite=Lax");
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/SessionResponse.java`：

```java
package com.gdzqlisu.datadesign.auth.auth;

public record SessionResponse(Long id, String displayName, String avatarUrl, String role,
                              String status, String appliedAt) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/TokenResponse.java`：

```java
package com.gdzqlisu.datadesign.auth.auth;

public record TokenResponse(String accessToken, long expiresInSeconds, String role, String status) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/AuthController.java`：

```java
package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.config.JwtProperties;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.oauth.AuthRequest;
import com.gdzqlisu.datadesign.auth.oauth.GitHubApiException;
import com.gdzqlisu.datadesign.auth.oauth.GitHubOAuthService;
import com.gdzqlisu.datadesign.auth.oauth.GitHubProfile;
import com.gdzqlisu.datadesign.auth.oauth.OAuthProperties;
import com.gdzqlisu.datadesign.auth.oauth.OAuthStateStore;
import com.gdzqlisu.datadesign.auth.token.InvalidRefreshTokenException;
import com.gdzqlisu.datadesign.auth.token.IssuedRefreshToken;
import com.gdzqlisu.datadesign.auth.token.RefreshCookieService;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenRecord;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenReuseException;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.LoginOutcome;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserProvisioningService;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final GitHubOAuthService github;
    private final OAuthStateStore stateStore;
    private final OAuthProperties oauthProperties;
    private final UserProvisioningService provisioning;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookieService cookies;
    private final JwtService jwt;
    private final JwtProperties jwtProperties;
    private final UserRepository users;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AuthController(GitHubOAuthService github,
                          OAuthStateStore stateStore,
                          OAuthProperties oauthProperties,
                          UserProvisioningService provisioning,
                          RefreshTokenService refreshTokens,
                          RefreshCookieService cookies,
                          JwtService jwt,
                          JwtProperties jwtProperties,
                          UserRepository users,
                          AuditService audit) {
        this.github = github;
        this.stateStore = stateStore;
        this.oauthProperties = oauthProperties;
        this.provisioning = provisioning;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.jwt = jwt;
        this.jwtProperties = jwtProperties;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/github/authorize")
    public ResponseEntity<Void> authorize() {
        if (!github.configured()) {
            return redirect("/login?error=provider_not_configured");
        }
        String state = UUID.randomUUID().toString();
        String verifier = randomUrlSafe(32);
        stateStore.save(state, new AuthRequest(verifier));
        return redirectTo(github.authorizeUrl(state, codeChallenge(verifier)));
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        AuthRequest authRequest = stateStore.consume(state);
        if (authRequest == null) {
            return redirect("/login?error=state_expired");
        }
        if (error != null || code == null || code.isBlank()) {
            return redirect("/login?error=oauth_failed");
        }

        GitHubProfile profile;
        try {
            profile = github.exchange(code, authRequest.codeVerifier());
        } catch (GitHubApiException e) {
            return redirect("/login?error=oauth_failed");
        }

        LoginOutcome outcome = provisioning.login(profile, request);
        User user = outcome.user();

        if (user.getStatus() == UserStatus.DISABLED) {
            cookies.clear(response);
            return redirect("/login?error=disabled");
        }

        cookies.write(response, refreshTokens.issue(user.getId(), user.getTokenVersion(), userAgent(request)));
        return switch (user.getStatus()) {
            case ACTIVE -> redirect("/auth/callback");
            case PENDING -> redirect("/pending");
            case REJECTED -> redirect("/rejected");
            case DISABLED -> redirect("/login?error=disabled");
        };
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(HttpServletRequest request) {
        User user = requireCookieUser(request);
        return ResponseEntity.ok(new SessionResponse(
                user.getId(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().toString()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        requireSameOrigin(request);
        String raw = cookies.read(request).orElseThrow(() -> unauthorized("unauthenticated", "缺少刷新令牌"));

        IssuedRefreshToken rotated;
        try {
            rotated = refreshTokens.rotate(raw, userAgent(request));
        } catch (RefreshTokenReuseException e) {
            cookies.clear(response);
            audit.recordFromRequest(e.getUserId(), AuditEvent.TOKEN_REVOKED, "LOCAL", request,
                    Map.of("reason", "refresh_token_reuse"));
            throw unauthorized("token_reuse", "刷新令牌被重复使用，已撤销该账号的全部会话");
        } catch (InvalidRefreshTokenException e) {
            cookies.clear(response);
            throw unauthorized("invalid_refresh_token", e.getMessage());
        }

        User user = users.findById(rotated.userId()).orElseThrow(
                () -> unauthorized("user_not_found", "账号不存在"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            cookies.clear(response);
            throw new ApiException(HttpStatus.FORBIDDEN, "account_not_active", "账号尚未通过审批或已被停用");
        }

        cookies.write(response, rotated);
        String accessToken = jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
        return ResponseEntity.ok(new TokenResponse(accessToken,
                jwtProperties.accessTtl().toSeconds(), user.getRole().name(), user.getStatus().name()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        cookies.read(request).ifPresent(raw -> {
            try {
                RefreshTokenRecord record = refreshTokens.inspect(raw);
                refreshTokens.revoke(raw);
                audit.recordFromRequest(record.userId(), AuditEvent.LOGOUT, "LOCAL", request, null);
            } catch (RuntimeException ignored) {
                refreshTokens.revoke(raw);
            }
        });
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    private User requireCookieUser(HttpServletRequest request) {
        String raw = cookies.read(request).orElseThrow(() -> unauthorized("unauthenticated", "缺少会话"));
        RefreshTokenRecord record;
        try {
            record = refreshTokens.inspect(raw);
        } catch (RefreshTokenReuseException e) {
            throw unauthorized("token_reuse", "刷新令牌被重复使用，请重新登录");
        } catch (InvalidRefreshTokenException e) {
            throw unauthorized("invalid_refresh_token", e.getMessage());
        }
        return users.findById(record.userId()).orElseThrow(() -> unauthorized("user_not_found", "账号不存在"));
    }

    private ResponseEntity<Void> redirect(String consolePath) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthProperties.consoleBaseUrl() + consolePath))
                .build();
    }

    private ResponseEntity<Void> redirectTo(String absoluteUrl) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(absoluteUrl)).build();
    }

    /**
     * refresh token 走 Cookie，必须确认请求确实来自我们自己的前端 origin。
     * 浏览器跨站发起的请求一定带 Origin，不同源直接拒绝；
     * curl、测试这类非浏览器客户端不带 Origin，放行。
     */
    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        String allowed = oauthProperties.consoleBaseUrl();
        if (allowed == null
                || !origin.replaceAll("/+$", "").equalsIgnoreCase(allowed.replaceAll("/+$", ""))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "cross_origin", "刷新令牌只能由同源前端发起");
        }
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        return agent == null ? "" : agent;
    }

    private String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
```

在 `auth-service/src/main/resources/application.yml` 的 `auth:` 块下追加：

```yaml
  cookie-secure: ${COOKIE_SECURE:false}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='RefreshTokenServiceTest,AuthControllerTest'
```

预期：`BUILD SUCCESS`，17 个测试通过（RefreshTokenService 8 个 + AuthController 9 个，
其中 `refreshDetectsTokenReuseAndRevokesChain` 与 `refreshIssuesAccessTokenOnlyForActiveUser` 各断言多点）。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 认证控制器、会话查询与刷新令牌 Cookie"
```

---

### Task 11: 管理员 API（审批、改角色、禁用）

**Files:**
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/UserAdminService.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/AdminUserController.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/ApproveRequest.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/RoleChangeRequest.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/UserSummaryResponse.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/admin/AdminUserControllerTest.java`

**设计要点：** 改角色与禁用必须同时做三件事，少一件就会出现「已经被踢下线但令牌还能用」：
1. `users.token_version` 自增（写库）
2. `TokenVersionCache.evict`（清缓存，让生效接近即时）
3. `RefreshTokenService.revokeAllForUser`（撤掉刷新令牌，否则对方还能刷新出新 access token）

另外管理员不能对自己禁用或降级。这不是产品偏好，是防呆：一旦做错就再也没人能进系统了。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/admin/AdminUserControllerTest.java`：

```java
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

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isUnauthorized());
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

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isUnauthorized());
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=AdminUserControllerTest
```

预期：编译失败，`cannot find symbol: class ApproveRequest`。

- [ ] **Step 3: 写最小实现**

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/ApproveRequest.java`：

```java
package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.user.Role;
import jakarta.validation.constraints.NotNull;

public record ApproveRequest(@NotNull Role role) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/RoleChangeRequest.java`：

```java
package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.user.Role;
import jakarta.validation.constraints.NotNull;

public record RoleChangeRequest(@NotNull Role role) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/UserSummaryResponse.java`：

```java
package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.user.User;

import java.time.Instant;

public record UserSummaryResponse(Long id, String displayName, String email, String avatarUrl,
                                  String role, String status, Instant createdAt, Instant lastLoginAt) {

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getDisplayName(), user.getEmail(),
                user.getAvatarUrl(), user.getRole().name(), user.getStatus().name(),
                user.getCreatedAt(), user.getLastLoginAt());
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/UserAdminService.java`：

```java
package com.gdzqlisu.datadesign.auth.admin;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.security.AuthenticatedUser;
import com.gdzqlisu.datadesign.auth.security.TokenVersionCache;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class UserAdminService {

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final TokenVersionCache tokenVersions;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RefreshTokenService refreshTokens,
                            TokenVersionCache tokenVersions, AuditService audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokenVersions = tokenVersions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> listByStatus(UserStatus status) {
        return users.findAllByStatusOrderByCreatedAtAsc(status).stream()
                .map(UserSummaryResponse::from)
                .toList();
    }

    @Transactional
    public UserSummaryResponse approve(long userId, Role role, AuthenticatedUser admin,
                                       HttpServletRequest request) {
        User user = require(userId);
        user.approve(role, admin.id());
        users.saveAndFlush(user);
        audit.recordFromRequest(userId, AuditEvent.APPROVED, "LOCAL", request,
                Map.of("role", role.name(), "adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse reject(long userId, AuthenticatedUser admin, HttpServletRequest request) {
        User user = require(userId);
        user.reject();
        users.saveAndFlush(user);
        audit.recordFromRequest(userId, AuditEvent.REJECTED, "LOCAL", request,
                Map.of("adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse changeRole(long userId, Role role, AuthenticatedUser admin,
                                          HttpServletRequest request) {
        requireNotSelf(userId, admin, "不能修改自己的角色");
        User user = require(userId);
        user.changeRole(role);
        users.saveAndFlush(user);
        invalidateSessions(userId);
        audit.recordFromRequest(userId, AuditEvent.ROLE_CHANGED, "LOCAL", request,
                Map.of("role", role.name(), "adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public UserSummaryResponse disable(long userId, AuthenticatedUser admin, HttpServletRequest request) {
        requireNotSelf(userId, admin, "不能禁用自己的账号");
        User user = require(userId);
        user.disable();
        users.saveAndFlush(user);
        invalidateSessions(userId);
        audit.recordFromRequest(userId, AuditEvent.USER_DISABLED, "LOCAL", request,
                Map.of("adminId", admin.id()));
        return UserSummaryResponse.from(user);
    }

    private void invalidateSessions(long userId) {
        tokenVersions.evict(userId);
        refreshTokens.revokeAllForUser(userId);
    }

    private void requireNotSelf(long userId, AuthenticatedUser admin, String message) {
        if (admin.id() != null && admin.id() == userId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_modify_self", message);
        }
    }

    private User require(long userId) {
        return users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "账号不存在"));
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/admin/AdminUserController.java`：

```java
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
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest=AdminUserControllerTest
```

预期：`BUILD SUCCESS`，9 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd .. && git add auth-service && git commit -m "feat(auth-service): 管理员审批、改角色与禁用

禁用与降权同时自增 token_version、清缓存并撤销刷新令牌，
让被操作的账号当场掉线；管理员不能对自己执行这两类操作。"
```

---

### Task 12: 破窗管理员与登录限流

**Files:**
- Modify: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/user/User.java`（加 `repairAsBreakGlass`）
- Modify: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/SecurityConfig.java`（加 `PasswordEncoder` Bean）
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminProperties.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminInitializer.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/LoginRateLimiter.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginRequest.java`
- Create: `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginController.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminInitializerTest.java`
- Test: `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginTest.java`

**设计要点：** 破窗账号是配置驱动的——每次启动都从环境变量校正，所以运维就算手滑把它禁用或降级，
重启即恢复。密码只存 BCrypt 哈希，明文从不落库、不入日志。入口不出现在任何前端导航里。

- [ ] **Step 1: 先写失败的测试**

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminInitializerTest.java`：

```java
package com.gdzqlisu.datadesign.auth.bootstrap;

import com.gdzqlisu.datadesign.auth.IntegrationTestBase;
import com.gdzqlisu.datadesign.auth.user.Role;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "auth.break-glass.username=break-glass-admin",
        "auth.break-glass.password-hash=$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
})
class BreakGlassAdminInitializerTest extends IntegrationTestBase {

    @Autowired
    private UserRepository users;

    @Test
    void createsActiveAdminOnStartup() {
        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.isBreakGlass()).isTrue();
        assertThat(admin.getPasswordHash()).startsWith("$2a$");
    }

    @Test
    void repairsTamperedAccountOnNextStartup() {
        User admin = users.findByDisplayName("break-glass-admin").orElseThrow();
        admin.disable();
        admin.changeRole(Role.VIEWER);
        users.saveAndFlush(admin);

        User reloaded = users.findById(admin.getId()).orElseThrow();
        boolean changed = reloaded.repairAsBreakGlass(
                "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        users.saveAndFlush(reloaded);

        User repaired = users.findById(admin.getId()).orElseThrow();
        assertThat(changed).isTrue();
        assertThat(repaired.getRole()).isEqualTo(Role.ADMIN);
        assertThat(repaired.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
```

创建 `auth-service/src/test/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginTest.java`：

```java
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
        "auth.break-glass.password-hash=$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
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
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='BreakGlassAdminInitializerTest,LocalLoginTest'
```

预期：编译失败，`cannot find symbol: method repairAsBreakGlass`。

- [ ] **Step 3: 写最小实现**

在 `User.java` 中插入：

```java
    /**
     * 每次启动按配置校正破窗账号。返回是否有字段被改动。
     */
    public boolean repairAsBreakGlass(String configuredPasswordHash) {
        boolean changed = false;
        if (this.role != Role.ADMIN) {
            this.role = Role.ADMIN;
            changed = true;
        }
        if (this.status != UserStatus.ACTIVE) {
            this.status = UserStatus.ACTIVE;
            changed = true;
        }
        if (!this.breakGlass) {
            this.breakGlass = true;
            changed = true;
        }
        if (configuredPasswordHash != null && !configuredPasswordHash.equals(this.passwordHash)) {
            this.passwordHash = configuredPasswordHash;
            changed = true;
        }
        if (changed) {
            this.updatedAt = Instant.now();
        }
        return changed;
    }
```

在 `SecurityConfig` 中追加一个 Bean：

```java
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
```

（补上 `import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;` 与
`import org.springframework.security.crypto.password.PasswordEncoder;`。）

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminProperties.java`：

```java
package com.gdzqlisu.datadesign.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.break-glass")
public record BreakGlassAdminProperties(String username, String passwordHash) {

    public boolean configured() {
        return username != null && !username.isBlank()
                && passwordHash != null && !passwordHash.isBlank();
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/bootstrap/BreakGlassAdminInitializer.java`：

```java
package com.gdzqlisu.datadesign.auth.bootstrap;

import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserIdentity;
import com.gdzqlisu.datadesign.auth.user.UserIdentityRepository;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class BreakGlassAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassAdminInitializer.class);

    private final BreakGlassAdminProperties properties;
    private final UserRepository users;
    private final UserIdentityRepository identities;

    public BreakGlassAdminInitializer(BreakGlassAdminProperties properties,
                                      UserRepository users,
                                      UserIdentityRepository identities) {
        this.properties = properties;
        this.users = users;
        this.identities = identities;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.warn("未配置破窗管理员（auth.break-glass.username / password-hash）；"
                    + "GitHub 不可用时将无法登录系统");
            return;
        }

        Optional<User> existing = users.findByDisplayName(properties.username());
        if (existing.isEmpty()) {
            User created = users.saveAndFlush(
                    User.newBreakGlass(properties.username(), properties.passwordHash()));
            identities.saveAndFlush(UserIdentity.local(created));
            log.info("已创建破窗管理员 {}（id={}）", properties.username(), created.getId());
            return;
        }

        User user = existing.get();
        if (user.repairAsBreakGlass(properties.passwordHash())) {
            users.saveAndFlush(user);
            log.warn("破窗管理员 {} 的账号状态与配置不一致，已按配置校正", properties.username());
        }
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/security/LoginRateLimiter.java`：

```java
package com.gdzqlisu.datadesign.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录尝试限流。计数器放 Redis，多实例部署也能生效。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String PREFIX = "rl:login:";

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true 表示本次尝试允许放行，false 表示已超限
     */
    public boolean tryAcquire(String scope) {
        String key = PREFIX + scope;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count == null || count <= MAX_ATTEMPTS;
    }

    public void reset(String scope) {
        redis.delete(PREFIX + scope);
    }
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginRequest.java`：

```java
package com.gdzqlisu.datadesign.auth.auth;

import jakarta.validation.constraints.NotBlank;

public record LocalLoginRequest(@NotBlank String username, @NotBlank String password) {
}
```

创建 `auth-service/src/main/java/com/gdzqlisu/datadesign/auth/auth/LocalLoginController.java`：

```java
package com.gdzqlisu.datadesign.auth.auth;

import com.gdzqlisu.datadesign.auth.audit.AuditEvent;
import com.gdzqlisu.datadesign.auth.audit.AuditService;
import com.gdzqlisu.datadesign.auth.common.ApiException;
import com.gdzqlisu.datadesign.auth.config.JwtProperties;
import com.gdzqlisu.datadesign.auth.config.JwtService;
import com.gdzqlisu.datadesign.auth.security.LoginRateLimiter;
import com.gdzqlisu.datadesign.auth.token.RefreshCookieService;
import com.gdzqlisu.datadesign.auth.token.RefreshTokenService;
import com.gdzqlisu.datadesign.auth.user.User;
import com.gdzqlisu.datadesign.auth.user.UserRepository;
import com.gdzqlisu.datadesign.auth.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/local")
public class LocalLoginController {

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookieService cookies;
    private final JwtService jwt;
    private final JwtProperties jwtProperties;
    private final LoginRateLimiter rateLimiter;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;

    public LocalLoginController(UserRepository users,
                                RefreshTokenService refreshTokens,
                                RefreshCookieService cookies,
                                JwtService jwt,
                                JwtProperties jwtProperties,
                                LoginRateLimiter rateLimiter,
                                AuditService audit,
                                PasswordEncoder passwordEncoder) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.jwt = jwt;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LocalLoginRequest body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        String ip = AuditService.clientIp(request);
        if (!rateLimiter.tryAcquire("ip:" + ip) || !rateLimiter.tryAcquire("user:" + body.username())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "登录尝试过于频繁，请 15 分钟后再试");
        }

        User user = users.findByDisplayName(body.username())
                .filter(User::isBreakGlass)
                .orElse(null);

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(body.password(), user.getPasswordHash())) {
            audit.recordFromRequest(user == null ? null : user.getId(), AuditEvent.LOGIN_FAILED,
                    "LOCAL", request, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "用户名或密码不正确");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            audit.recordFromRequest(user.getId(), AuditEvent.LOGIN_DISABLED, "LOCAL", request, null);
            throw new ApiException(HttpStatus.FORBIDDEN, "account_not_active", "账号当前不可用");
        }

        rateLimiter.reset("user:" + body.username());
        user.recordLogin();
        users.saveAndFlush(user);
        audit.recordFromRequest(user.getId(), AuditEvent.BREAK_GLASS_LOGIN, "LOCAL", request, null);

        cookies.write(response, refreshTokens.issue(user.getId(), user.getTokenVersion(),
                request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent")));
        String accessToken = jwt.issue(user.getId(), user.getRole().name(), user.getTokenVersion());
        return ResponseEntity.ok(new TokenResponse(accessToken,
                jwtProperties.accessTtl().toSeconds(), user.getRole().name(), user.getStatus().name()));
    }
}
```

在 `auth-service/src/main/resources/application.yml` 的 `auth:` 块下追加：

```yaml
  break-glass:
    username: ${BREAK_GLASS_ADMIN_USERNAME:}
    password-hash: ${BREAK_GLASS_ADMIN_PASSWORD_HASH:}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd auth-service && ./scripts/mvn -q test -Dtest='BreakGlassAdminInitializerTest,LocalLoginTest'
```

预期：`BUILD SUCCESS`，6 个测试通过。

- [ ] **Step 5: 全量回归并提交**

```bash
cd auth-service && ./scripts/mvn -q test
cd .. && git add auth-service && git commit -m "feat(auth-service): 破窗管理员与登录限流，后端认证能力完成"
```

---

---

## 计划自检（writing-plans 复核）

### Spec 覆盖对照

| Spec 章节 | 覆盖它的 Task | 说明 |
|---|---|---|
| §3.1 仓库结构 | Task 1 | |
| §3.2 运行时拓扑 | Task 1、Task 2 | 后端 8080；5173 / nginx 归计划 B |
| §3.3 基础设施 | Task 2 | 复用本机 `mysql:8.0`、`redis:7-alpine`；nginx 在 edge profile，计划 B 才用 |
| §4.1 users | Task 2、Task 3 | |
| §4.2 user_identities | Task 2、Task 3 | |
| §4.3 audit_logs | Task 2、Task 7 | 刻意不加外键，账号删除也要留痕 |
| §4.4 Redis 键 | Task 2（前缀约定）、Task 5（`rt:`）、Task 6（`tv:`）、Task 8（`oauth:state:`）、Task 12（`login:rl:`） | |
| §5.1 GitHub 授权码 + PKCE | Task 8、Task 9 | state 一次性，PKCE S256 |
| §5.2 待审批与被拒 | Task 9、Task 10 | 首登落 PENDING，签发只读会话 |
| §5.3 管理员操作 | Task 11 | 审批 + 赋角色 + 禁用；禁用或降级自增 token_version 并撤销刷新令牌 |
| §5.4 破窗管理员 | Task 12 | 每次启动按环境变量校正，重启即恢复 |
| §5.5 令牌 | Task 4、Task 5、Task 6 | 短效 JWT + Redis 中可吊销的刷新令牌 |
| §5.6 前端刷新 | Task 10 | `POST /api/auth/refresh` 轮换 + 复用检测 |
| §5.7 安全基线 | Task 6、Task 8、Task 10、Task 12 | state + PKCE、同源部署（不配 CORS）、Origin 校验、登录限流 |
| §6 前端 | **计划 B** | 本计划不含前端 |
| §7 遗留与后续子项目 | — | 明确不在本计划范围内 |
| §8 现有资产处置 | — | 文档层面已处理 |
| §9 环境变量 | Task 1、Task 8、Task 12 | application.yml + 各 Properties 类 |
| §10 仓库与协作 | 每个 Task 的 Step 5 | 一个 Task 一个提交 |

### 自检中已修正的问题

- `ApiException` / `ApiExceptionHandler` 原先被 Task 11、12 引用却无人创建 → 在 Task 10 Step 3 补齐
- Task 10 原用 `ResponseStatusException`，与测试断言的 `$.code` 对不上（ProblemDetail 输出的是 `detail`）→ 统一改为 `ApiException`
- Task 6 承诺「刷新接口校验 Origin」，Task 10 却没实现 → 补 `requireSameOrigin` 与 `refreshRejectsCrossOriginRequest`
- `AuditService` 原用 `@Transactional(REQUIRES_NEW)`，被 `recordFromRequest` 自调用绕过，「审计失败不拖挂登录」不成立 → 改用 `TransactionTemplate` 编程式事务，序列化与提交都在 try 内
- `TokenVersionCache.evictAll()` 无调用方且依赖 `KEYS` 扫描 → 删除
- `AuthRequest` 未使用的 `redirectTarget` 字段 → 删除
- `IntegrationTestBase` 原对静态字段标 `@ServiceConnection`（不生效）→ 改为 `@DynamicPropertySource`
- `AuthController.randomUrlSafe` 原为 static 却要用注入的 `random` → 改为实例方法
- 文件结构清单与实际 Task 清单比对后重写（原列了不存在的 `RedisConfig`、`TokenPair`、`GitHubOAuthSuccessHandler` 等）
- 每个 Task 的「N 个测试通过」与实际 `@Test` 数量逐一核对（Task 10 由 15 更正为 17）

### 一致性与占位符检查

- 占位符扫描：无 `TBD` / `TODO` / `FIXME` / 「待补」残留
- 计划内所有 `com.gdzqlisu.*` 导入都能对应到某个 Task 声明的文件路径
- 每个 Task 都是「写失败测试 → 确认失败 → 最小实现 → 确认通过 → 提交」的闭环，可独立交给一个执行者
