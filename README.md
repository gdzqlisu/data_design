# data_design

信贷风控决策引擎。

整个引擎包含规则引擎、决策流编排、变量与数据接入、执行监控、控制台等多个相互独立的
子系统，按子项目逐个推进：每个子项目独立走「设计 → spec → 实现计划 → TDD 实现」。

## 当前进度

**子项目 1：认证与账号体系 + 控制台骨架**

- 后端认证服务（计划 A）：**已完成**，12 个 Task、64 个测试全部通过
- 前端控制台（计划 B）：待开始

| 产物 | 位置 |
|---|---|
| 设计文档 | `docs/superpowers/specs/2026-10-07-auth-and-console-shell-design.md` |
| 后端实现计划 | `docs/superpowers/plans/2026-10-07-auth-service-backend.md` |
| 视觉方向 | `docs/design/visual-style-options.html`（选定 A · 稳健金融蓝） |

一句话概括这个子项目：GitHub OAuth 登录为主、首次登录待审批、管理员审批并分配角色、
保留一个本地破窗管理员，登录后进入「稳健金融蓝」风格的控制台骨架。

## 后端已实现的能力

- GitHub OAuth 授权码 + PKCE（state 一次性、S256）
- 首登待审批：新身份落 `PENDING`，管理员审批时一并赋角色
- 管理员操作：审批 / 拒绝 / 改角色 / 禁用，禁用或降级即时生效
- 令牌：15 分钟 JWT（`Authorization` 头）+ Redis 中的可吊销刷新令牌（`ds_rt` Cookie，
  轮换 + 复用检测，复用即撤销该账号整条链）
- 审计日志：12 类事件，写入失败不影响主流程
- 破窗管理员：配置驱动，每次启动按环境变量校正
- 登录限流：按 IP + 账号，走 Redis

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3.5 / **Java 17** / Spring Security 6 / Spring Data JPA / Flyway |
| 前端 | React 18 / Vite / TypeScript / Ant Design 5（计划 B） |
| 存储 | MySQL 8（用户、身份、审计）、Redis 7（令牌、限流、OAuth2 请求暂存） |
| 测试 | JUnit 5 + AssertJ + MockMvc + Testcontainers（复用本机镜像） |
| 运行 | Docker（本机 `mysql:8.0`、`redis:7-alpine`；nginx 走 edge profile） |

Java 版本说明：设计文档写的是 21，本机只有 8/11/17/25，Spring Boot 3.3 的最低要求是 17，
因此实现固定用 17。

## 目录结构

```
auth-service/     Spring Boot 认证服务（已实现）
deploy/           docker-compose（MySQL + Redis，nginx 在 edge profile）
docs/             设计文档、实现计划与视觉参考
console-web/      React 控制台（计划 B，尚未创建）
```

## 本地运行

### 1. 起依赖

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis
```

本机 3306 / 6379 已被别的项目占用时，用环境变量换端口（compose 里是可覆盖的）：

```bash
MYSQL_PORT=13306 REDIS_PORT=16379 docker compose -f deploy/docker-compose.yml up -d mysql redis
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:13306/data_design?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
SPRING_DATA_REDIS_PORT=16379 ./scripts/mvn spring-boot:run
```

### 2. 跑测试

```bash
cd auth-service && ./scripts/mvn clean test
```

**必须用 `./scripts/mvn`，不要用裸 `mvn`。** 本机 `~/.mavenrc` 把 `JAVA_HOME` 写死成
Java 8 并会覆盖环境变量，包装脚本用 `MAVEN_SKIP_RC=1` 跳过它并固定 JDK 17。

测试用 Testcontainers 起临时 MySQL/Redis，镜像复用本机已有的 `mysql:8.0` 与 `redis:7-alpine`，
不联网拉取。

### 3. 起服务

```bash
cd auth-service && ./scripts/mvn spring-boot:run
```

### 4. 需要配置的环境变量

| 变量 | 用途 | 缺省 |
|---|---|---|
| `JWT_SECRET` | 访问令牌签名密钥，至少 32 字节 | 仅开发用的固定值 |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth 应用凭据 | 空（未配置时登录接口返回 `provider_not_configured`） |
| `GITHUB_REDIRECT_URI` | 回调地址 | `http://localhost:5173/api/auth/github/callback` |
| `CONSOLE_BASE_URL` | 前端地址，同时用于刷新接口的 Origin 校验 | `http://localhost:5173` |
| `BREAK_GLASS_ADMIN_USERNAME` / `BREAK_GLASS_ADMIN_PASSWORD_HASH` | 破窗管理员 | 空（不创建） |
| `COOKIE_SECURE` | 刷新 Cookie 是否带 `Secure` | `false` |

## 本地环境要求

- JDK 17（Homebrew `openjdk@17`）、Maven 3.9+
- Node.js 20+（计划 B 用）
- Docker Desktop（复用本机已有的 `mysql:8.0` 与 `redis:7-alpine`）
