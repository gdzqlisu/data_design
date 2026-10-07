# data_design

信贷风控决策引擎。

整个引擎包含规则引擎、决策流编排、变量与数据接入、执行监控、控制台等多个相互独立的
子系统，按子项目逐个推进：每个子项目独立走「设计 → spec → 实现计划 → TDD 实现」。

## 当前进度

**子项目 1：认证与账号体系 + 控制台骨架** —— 设计已完成，待评审。

- 设计文档：[`docs/superpowers/specs/2026-10-07-auth-and-console-shell-design.md`](docs/superpowers/specs/2026-10-07-auth-and-console-shell-design.md)
- 视觉方向：[`docs/design/visual-style-options.html`](docs/design/visual-style-options.html)（选定 A · 稳健金融蓝）

一句话概括这个子项目：GitHub OAuth 登录为主、首次登录待审批、管理员审批并分配角色、
保留一个本地破窗管理员，登录后进入「稳健金融蓝」风格的控制台骨架。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3 / Java 21 / Spring Security 6 / Spring Data JPA |
| 前端 | React 18 / Vite / TypeScript / Ant Design 5 |
| 存储 | MySQL 8（用户、身份、审计）、Redis 7（令牌、限流、OAuth2 请求暂存） |
| 运行 | Docker（复用本机 `mysql:8.0`、`redis:7-alpine` 镜像） |

## 目录结构

```
auth-service/     Spring Boot 后端（待创建）
console-web/      React 控制台（待创建）
deploy/           docker-compose 与 nginx 配置（待创建）
docs/             设计文档与视觉参考
```

后端与前端的代码尚未开始，当前仓库只有设计与参考资料。

## 本地环境要求

- JDK 21、Maven 3.9+
- Node.js 20+
- Docker Desktop（已装，镜像复用本机已有的 `mysql:8.0` 与 `redis:7-alpine`）
