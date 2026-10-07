# 认证与账号体系 + 控制台骨架 设计文档

> 这是「信贷风控决策引擎」的第一个子项目。决策引擎本身包含规则引擎、决策流编排、
> 变量与数据接入、执行监控、控制台等多个相互独立的子系统，不能塞进一份设计里。
> 本文只覆盖第一个子项目：**认证与账号体系**，外加把控制台骨架立起来。

**日期**：2026-10-07
**状态**：待用户评审

---

## 1. 目标

为内部研发与策略人员提供一个可以登录、且登录后能进入控制台的账号体系。具体交付：

1. GitHub OAuth 登录（主力登录方式）
2. 首次登录待审批的准入模型
3. 管理员审批与用户管理能力
4. 本地破窗管理员账号
5. 一套按「稳健金融蓝」风格落地的控制台骨架

**成功标准**：

- 一个未登记的 GitHub 账号首次登录后，只能看到待审批页，访问任何业务接口都被拒绝
- 管理员审批通过并分配角色后，该用户无需重新登录，下一次请求即可进入控制台
- 管理员禁用某账号后，该用户的既有令牌在 60 秒内失效
- 在 GitHub 不可用时，破窗管理员仍能登录并完成上述所有操作
- 登录页与控制台骨架使用统一设计 token，不出现 Ant Design 默认蓝

**非目标（本次不做）**：决策引擎业务功能、业务操作审计、多租户/机构隔离、
密码找回与邮箱验证码、GitHub 组织成员校验。

---

## 2. 背景与已确认的决策

| 项 | 决策 | 理由 |
|---|---|---|
| 使用者 | 内部研发、策略人员自用 | GitHub 账号天然存在，不需要密码体系 |
| 技术栈 | Spring Boot 3.3 / Java 21 + React 18 + Vite + TS + Ant Design 5 | 与公司既有 Java 体系（`kingzeus` 等）一致 |
| 视觉风格 | A · 稳健金融蓝 | 银行/风控系统的熟悉语言，高密度页面表现稳 |
| 准入模型 | 首登待审批 | GitHub 账号 ≠ 公司身份，开放自助等于把策略配置暴露给互联网 |
| 身份来源 | GitHub 为主 + 本地破窗管理员 | 避免 GitHub 不可用时彻底锁死系统 |
| 角色 | `role` 独立字段（枚举），本次启用 ADMIN / MEMBER，预留 STRATEGIST / VIEWER | 将来加角色只加枚举值，不改表结构 |
| 审批 | 通过时同时分配角色 | 一步到位，避免二次操作 |
| 会话方案 | 前后端分离 + JWT（方案二），配短有效期与可吊销刷新令牌 | 用户选定 |
| 子项目边界 | 认证能力 + 控制台骨架 | 用户管理页做完整留作下一个子项目 |

---

## 3. 架构

### 3.1 仓库结构

```
data_design/
├─ auth-service/            Spring Boot 3.3 / Java 21
│  ├─ config/               SecurityConfig、RedisConfig、JwtConfig、CorsConfig
│  ├─ oauth/                GitHub OAuth2 登录成功处理器
│  ├─ user/                 User / UserIdentity 领域与仓储
│  ├─ token/                JWT 签发、刷新轮换、吊销
│  ├─ admin/                审批 + 用户管理 API
│  └─ audit/                登录与敏感操作审计
├─ console-web/             React 18 + Vite + TypeScript + Ant Design 5
│  ├─ api/                  axios 封装（401 单飞刷新）
│  ├─ auth/                 AuthProvider、useAuth、RequireAuth
│  ├─ layouts/              控制台骨架
│  └─ pages/                Login / LocalLogin / Pending / Rejected / Dashboard / Approvals / Users
├─ deploy/                  docker-compose（mysql、redis、nginx）+ nginx.conf
├─ docs/superpowers/specs/  本设计文档
└─ README.md
```

### 3.2 运行时拓扑

- `auth-service`：8080
- Vite 开发服务器：5173，代理 `/api` → 8080
- 生产：Nginx 统一入口，前端静态产物与 `/api` 反向代理到同一 origin

**为什么前后端分离部署却保持浏览器同源**：前后端各自独立构建、独立发布，但对浏览器呈现为
同一 origin。这样 refresh token 的 Cookie 可以用 `SameSite=Lax` 而非 `None`，避免
`SameSite=None` 带来的一整类跨站鉴权与 CSRF 问题。若最终部署确实是两个不同域名，
需要同步修改本节的 Cookie 策略与 CORS 白名单。

### 3.3 基础设施（复用本机 Docker 镜像）

宿主机已装 Docker Desktop 29.3.1 + Compose v5.1.1，本地镜像列表里已有以下镜像，
**不联网拉取、不自行构建**：

| 组件 | 使用镜像 | 职责 |
|---|---|---|
| MySQL | `mysql:8.0` | 用户、身份、审计日志 |
| Redis | `redis:7-alpine` | refresh token、OAuth2 authorization request、`token_version` 缓存、登录限流计数 |

- 固定显式 tag，不使用 `latest`；compose 中不写 `build`
- MySQL 用命名卷持久化；Redis 仅作缓存与令牌存储，不做持久化
- 端口映射到宿主机 3306 / 6379，开发期 `auth-service` 与 `console-web` 在宿主机跑，走 `localhost`

**Nginx：本机没有对应镜像，也没有本机 nginx 二进制，需要从 Docker Hub 拉取。**

| 组件 | 使用镜像 | 获取方式 |
|---|---|---|
| Nginx | `nginx:1.27-alpine` | `docker pull nginx:1.27-alpine` |

- 开发期不启用 —— Vite dev server 代理 `/api` → 8080，浏览器侧已经是同源，不需要反代
- compose 中 nginx 服务放在 `edge` profile 下，首次使用前执行一次 `docker pull`，
  之后走本机镜像，不再重复拉取

---

## 4. 数据模型

### 4.1 `users`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | |
| `display_name` | VARCHAR | |
| `email` | VARCHAR NULL | GitHub 邮箱，未经企业域校验，不用于账号合并 |
| `avatar_url` | VARCHAR NULL | |
| `role` | ENUM | `ADMIN` / `MEMBER`（枚举预留 `STRATEGIST` / `VIEWER`） |
| `status` | ENUM | `PENDING` / `ACTIVE` / `DISABLED` / `REJECTED` |
| `is_break_glass` | BOOLEAN | 本地应急管理员标记 |
| `password_hash` | VARCHAR NULL | 仅破窗管理员有值，BCrypt |
| `token_version` | INT NOT NULL DEFAULT 0 | 自增即让该用户所有已签发 token 失效 |
| `approved_by` | BIGINT NULL | |
| `approved_at` | DATETIME NULL | |
| `created_at` / `updated_at` / `last_login_at` | DATETIME | |

### 4.2 `user_identities`

`id`、`user_id` FK、`provider`（`GITHUB` / `LOCAL`）、`provider_user_id`、
`provider_login`、`email`、`avatar_url`、`created_at`、`updated_at`，
唯一约束 `UNIQUE(provider, provider_user_id)`。

一个账号可挂多个身份。**不做「同一邮箱自动合并账号」**：GitHub 邮箱未经企业域校验，
自动合并等于开放一个越权入口；改为由管理员显式绑定。

### 4.3 `audit_logs`

`id`、`user_id` NULL、`event`、`provider`、`ip`、`user_agent`、`detail_json`、`created_at`。

事件类型：`LOGIN_SUCCESS`、`LOGIN_PENDING`、`LOGIN_REJECTED`、`LOGIN_DISABLED`、
`LOGIN_FAILED`、`APPROVED`、`REJECTED`、`ROLE_CHANGED`、`USER_DISABLED`、
`LOGOUT`、`TOKEN_REVOKED`、`BREAK_GLASS_LOGIN`。

只记录认证与账号事件，不记录业务操作（业务审计属于后续子项目）。

### 4.4 Redis 键

| 键 | 内容 | TTL |
|---|---|---|
| `rt:{jti}` | 用户 id、tokenVersion、UA 摘要、过期时间、轮换指针 | 7 天 |
| `oauth2:authreq:{state}` | OAuth2 授权请求（含 PKCE code_verifier） | 10 分钟 |
| `tv:{userId}` | `users.token_version` 缓存 | 60 秒 |
| `rl:login:{ip}` / `rl:login:{account}` | 登录限流计数 | 15 分钟 |

---

## 5. 认证流程

### 5.1 GitHub 登录（授权码 + PKCE）

```
浏览器 → GET  /api/auth/github/authorize
       → 302  GitHub 授权页（scope: read:user user:email；state + PKCE）
GitHub → 302  /api/auth/github/callback?code&state
后端   → 校验 state（Redis 取出即删）→ code 换 token → 拉 /user 与 /user/emails
       ├ 身份已存在 + ACTIVE   → 签发令牌，进控制台
       ├ 身份已存在 + PENDING  → /pending
       ├ 身份已存在 + DISABLED → /login?error=disabled（记审计）
       ├ 身份已存在 + REJECTED → /rejected
       └ 身份不存在            → 建 user(status=PENDING) + identity，记审计 → /pending
```

### 5.2 待审批与被拒

- 待审批页展示 GitHub 头像、昵称、提交时间，只能登出
- 后端对所有 `/api/**`（除认证相关白名单）强制校验 `status = ACTIVE`，
  前端页面只是提示，安全边界在后端

### 5.3 管理员操作

| 接口 | 行为 |
|---|---|
| `GET /api/admin/users?status=PENDING` | 审批队列 |
| `POST /api/admin/users/{id}/approve` | body `{ role }`；置 ACTIVE，写 `approved_by` / `approved_at` |
| `POST /api/admin/users/{id}/reject` | 置 REJECTED |
| `POST /api/admin/users/{id}/role` | 改角色，同时 `token_version` +1 |
| `POST /api/admin/users/{id}/disable` | 置 DISABLED，同时 `token_version` +1 |

- 管理员**不能**对自己执行禁用或降级，防止把系统锁死。此约束必须有对应测试用例。
- 改角色与禁用都会让目标用户既有令牌立即失效（不等 15 分钟过期）。
- 改角色与禁用时，在同一事务提交后主动删除 `tv:{userId}` 缓存键，使生效接近即时；
  60 秒 TTL 只作为兜底，不作为生效延迟的依据。

### 5.4 破窗管理员

- 环境变量：`BREAK_GLASS_ADMIN_USERNAME`、`BREAK_GLASS_ADMIN_PASSWORD_HASH`（BCrypt）
- 启动时若账号不存在则创建；存在则校正为 `ACTIVE` + `ADMIN`
- 环境变量缺失时服务正常启动，但打印 WARN
- 唯一走用户名密码的入口：`POST /api/auth/local/login`
- 前端入口 `/login/local` 不出现在任何导航或页面链接中
- 审计事件标记为 `BREAK_GLASS_LOGIN`

### 5.5 令牌

**access token**：JWT，15 分钟，仅存前端内存，不落 localStorage。
claims：`sub`(userId)、`role`、`tv`(token_version)、`jti`、`exp`。

**refresh token**：不透明随机串，7 天，httpOnly + Secure + SameSite=Lax Cookie。
Redis `rt:{jti}` 记录用户、tokenVersion、UA 摘要、过期时间、轮换指针。

- **轮换**：每次刷新作废旧 jti 并签发新 jti
- **复用检测**：若已作废的 jti 再次被使用，判定令牌被盗，撤销该用户整条刷新链并记审计

**每个受保护请求的校验顺序**：

1. 验签与 `exp`
2. `tv` 与 `users.token_version` 一致（`tv:{userId}` 缓存 60 秒，因此禁用最迟 60 秒生效）
3. `users.status = ACTIVE`
4. 放行

**登出**：删除 Redis 中该 refresh token 并清 Cookie；access token 自然过期。

### 5.6 前端刷新

axios 响应拦截器遇 401 → 单飞（single-flight）刷新，其余并发请求排队等待同一刷新结果；
刷新失败则清空内存令牌并跳转 `/login`。

### 5.7 安全基线

- GitHub OAuth 使用 `state` + PKCE
- CORS 只允许同源入口；开发期走 Vite proxy
- 登录接口按 IP + 账号限流（Redis）
- GitHub client secret 只存在于后端
- 页面重定向白名单校验，禁止开放重定向

---

## 6. 前端

### 6.1 路由

| 路径 | 页面 | 守卫 |
|---|---|---|
| `/login` | 登录页 | 未登录 |
| `/login/local` | 破窗入口（导航中不出现） | 未登录 |
| `/pending` | 待审批 | 已登录 + PENDING |
| `/rejected` | 已拒绝 | 已登录 + REJECTED |
| `/` | Dashboard 占位 | 已登录 + ACTIVE |
| `/admin/approvals` | 审批队列 | 已登录 + ADMIN |
| `/admin/users` | 用户列表（改角色、禁用） | 已登录 + ADMIN |

### 6.2 控制台骨架

- 左侧固定导航：深蓝底、Logo、分组菜单（决策中心 / 规则中心 / 监控中心 / 系统管理）。
  本次只有「系统管理 → 审批、用户」是通的，其余为占位项
- 顶栏：面包屑 + 环境标识（dev / prod 视觉强区分，防误操作）+ 用户菜单（头像、角色标签、登出）
- 内容区：统一由 `ConfigProvider` 注入设计 token，**不使用 Ant Design 默认蓝**
- 受保护路由由 `RequireAuth` 包装，未登录直接重定向，不闪白屏

### 6.3 视觉规范（风格 A · 稳健金融蓝）

| token | 值 |
|---|---|
| 页面背景 | `linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%)`，叠加 26px 网格线 `rgba(255,255,255,0.05)` |
| 卡片 | 背景 `#ffffff`，圆角 `6px`，顶部 `3px` 强调条，阴影 `0 18px 40px -18px rgba(0,0,0,0.6)` |
| 主色 | `#1d4ed8` |
| 文本 | 主 `#0f172a`，次 `#64748b`，弱 `#94a3b8` |
| 边框 | 输入框 `#d7dfec`，分隔线 `#eef1f6` |
| 控件圆角 | `4px` |
| 侧边导航底色 | `#0e2a58`，选中态 `#1d4ed8` |

原型参考：`docs/design/visual-style-options.html` 中的 A 卡片（随仓库提交，可直接在浏览器打开）。

---

## 7. 遗留与后续子项目

| 后续子项目 | 说明 |
|---|---|
| 用户管理页完整版 | 列表筛选、批量操作、登录审计查看 |
| 规则与策略配置 | 需要角色权限细化（`STRATEGIST` / `VIEWER` 启用） |
| 决策流编排 | 控制台核心，重交互画布 |
| 变量与数据接入 | 指标、名单、三方数据源 |
| 决策执行与监控 | 命中、评分、原因码、大盘 |
| 业务操作审计 | 与认证审计共用审计基础设施 |

---

## 8. 现有资产处置

`data_design/` 下存在一个已验证的 Node/Express 登录原型（`src/`、`views/`、`public/`、
`package.json`、`node_modules/`、`data/app.db`），原本用于验证 GitHub OAuth 全流程与
视觉方向。技术栈切换到 Java 后该代码不再复用，处置方式：移动到 `.archive/node-prototype/`，
保留以备查阅，并在 `.gitignore` 中排除（不进仓库）；`console-web` 按第 6.3 节重新实现视觉。

---

## 9. 环境变量

| 变量 | 用途 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` / `prod` |
| `MYSQL_HOST` `MYSQL_PORT` `MYSQL_DATABASE` `MYSQL_USER` `MYSQL_PASSWORD` | 数据库连接 |
| `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` | Redis 连接 |
| `GITHUB_CLIENT_ID` `GITHUB_CLIENT_SECRET` | GitHub OAuth App |
| `GITHUB_REDIRECT_URI` | 必须与 GitHub 后台登记的 callback URL 完全一致 |
| `JWT_SECRET` | HS256 签名密钥，长度不小于 32 字节 |
| `ACCESS_TOKEN_TTL_SECONDS` | 默认 900 |
| `REFRESH_TOKEN_TTL_SECONDS` | 默认 604800 |
| `CONSOLE_BASE_URL` | 登录后重定向白名单基准，防开放重定向 |
| `BREAK_GLASS_ADMIN_USERNAME` | 破窗管理员用户名 |
| `BREAK_GLASS_ADMIN_PASSWORD_HASH` | 破窗管理员 BCrypt 哈希 |

GitHub OAuth App 需登记 `{host}/api/auth/github/callback`，本地开发即
`http://localhost:5173/api/auth/github/callback`（经 Vite 代理）。

---

## 10. 仓库与协作

- 远端仓库：`https://github.com/gdzqlisu/data_design.git`（public，默认分支 `main`，当前为空仓库）
- 仓库根目录即 `data_design/`，`auth-service/` 与 `console-web/` 作为同级子目录
- `.gitignore` 覆盖：`node_modules/`、`target/`、`dist/`、`.env`、`data/`、`.archive/`、`.superpowers/`、`*.log`、`.DS_Store`
- 提交规范：Conventional Commits（`feat:` / `fix:` / `docs:` / `test:` / `chore:`）
- 首次提交内容：本 spec、`README.md`、`deploy/docker-compose.yml`、目录骨架、`.gitignore`
