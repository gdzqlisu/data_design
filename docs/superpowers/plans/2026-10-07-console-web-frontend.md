# 前端控制台实现计划（子项目 1 · 计划 B）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `console-web`：GitHub 登录 / 破窗登录、待审批与已拒绝页、控制台骨架（侧栏 + 顶栏 +
环境标识）、管理员审批与用户列表，视觉按 spec §6.3「风格 A · 稳健金融蓝」。

**Architecture:** Vite + React 18 SPA，只消费 `auth-service` 的 JSON API。access token 只存在内存里
（刷新页面即丢，靠 `ds_rt` Cookie 静默续期），refresh token 全程由后端写在 httpOnly Cookie 里，
前端**看不见也拿不到**。浏览器侧始终只与一个 origin 打交道：开发期 Vite 代理 `/api` → 8080，
生产期 Nginx 同时托管静态产物并反代 `/api`。

**Tech Stack:** React 18.3、TypeScript 5.6、Vite 5.4、Ant Design 5.29、react-router-dom 6.30、
Vitest 2.1 + Testing Library、jsdom

**Spec:** `docs/superpowers/specs/2026-10-07-auth-and-console-shell-design.md`（§6 前端、§6.3 视觉规范）

**前置：** 计划 A 已完成并推送，`auth-service` 的接口契约如下（本计划全部按它写）：

| 方法与路径 | 用途 | 成功 | 失败 |
|---|---|---|---|
| `GET /api/auth/github/authorize` | 浏览器整页跳转到 GitHub | 302 | 未配凭据时 302 → `/login?error=provider_not_configured` |
| `GET /api/auth/github/callback` | 后端回调，带 Cookie 跳回前端 | 302 → `/auth/callback`(ACTIVE) / `/pending` / `/rejected` | 302 → `/login?error=state_expired｜oauth_failed｜disabled` |
| `GET /api/auth/session` | 只读会话，用于启动引导 | 200 `{id,displayName,avatarUrl,role,status,appliedAt}` | 401 |
| `POST /api/auth/refresh` | 用 Cookie 换 access token | 200 `{accessToken,expiresInSeconds,role,status}` | 401 `unauthenticated｜invalid_refresh_token｜token_reuse`；403 `account_not_active｜cross_origin` |
| `POST /api/auth/logout` | 清 Cookie | 204 | — |
| `POST /api/auth/local/login` | 破窗登录 `{username,password}` | 200 同 refresh | 401 `invalid_credentials`；403 `account_not_active`；429 `too_many_attempts` |
| `GET /api/me` | 当前用户 | 200 `{id,displayName,email,avatarUrl,role,status,breakGlass}` | 401 `unauthenticated｜invalid_token｜token_revoked｜user_not_found`；403 `account_not_active` |
| `GET /api/admin/users?status=PENDING` | 按状态列用户 | 200 `[{id,displayName,email,avatarUrl,role,status,createdAt,lastLoginAt}]` | 401 / 403 `forbidden` |
| `POST /api/admin/users/{id}/approve` | 审批 `{role}` | 200 用户摘要 | 400 `cannot_modify_self`；404 `user_not_found` |
| `POST /api/admin/users/{id}/reject` | 拒绝 | 200 用户摘要 | 同上 |
| `POST /api/admin/users/{id}/role` | 改角色 `{role}` | 200 用户摘要 | 同上 |
| `POST /api/admin/users/{id}/disable` | 禁用 | 200 用户摘要 | 同上 |

错误响应体统一是 `{"code": "...", "message": "..."}`。

**本计划范围：** 只有前端。控制台里除「系统管理 → 审批 / 用户」外的菜单项都是占位，
不接后端；用户列表的筛选、批量操作、审计查看属于后续子项目（spec §7）。

---

## 环境前提

### 工具链

| 项 | 版本 |
|---|---|
| Node | v24.14.1 |
| npm | 11.11.0 |
| registry | `https://registry.npmmirror.com`（本机已配，无需改） |

依赖版本全部显式钉死，不用 `latest`：

| 包 | 版本 |
|---|---|
| react / react-dom | 18.3.1 |
| antd | 5.29.3 |
| react-router-dom | 6.30.6 |
| vite | 5.4.21 |
| @vitejs/plugin-react | 4.7.0 |
| typescript | 5.6.3 |
| vitest | 2.1.9 |
| @vitest/coverage-v8 | 2.1.9 |
| jsdom | 26.1.0 |
| @testing-library/react | 16.3.3 |
| @testing-library/jest-dom | 6.10.0 |
| @testing-library/user-event | 14.6.7 |
| @types/react | 18.3.31 |
| @types/react-dom | 18.3.7 |

### 后端必须先起来

前端所有请求都打 `/api`，开发期由 Vite 代理到 `http://localhost:8080`。
联调前先按 README 起 MySQL/Redis 与 `auth-service`。

### 测试环境的两个必备垫片

jsdom 没有 `matchMedia` 与 `ResizeObserver`，而 Ant Design 5 会用到它们，
不垫的话测试会在 `render` 阶段直接抛异常。Task 1 的 `src/test/setup.ts` 一次装好。

---

## 文件结构

```
console-web/
├─ package.json、tsconfig.json、tsconfig.node.json、vite.config.ts、index.html
├─ src/
│  ├─ main.tsx                     挂载 React 根
│  ├─ App.tsx                      ConfigProvider + AuthProvider + 路由
│  ├─ theme/tokens.ts              spec §6.3 的设计 token（唯一真相）
│  ├─ theme/antdTheme.ts           把 token 翻译成 Ant Design 主题
│  ├─ styles/global.css            页面渐变背景 + 网格线 + 卡片
│  ├─ api/client.ts                fetch 封装：Bearer、ApiError、401 静默续期重试
│  ├─ api/auth.ts                  session / refresh / logout / localLogin / authorize 跳转
│  ├─ api/admin.ts                 用户列表、审批、拒绝、改角色、禁用
│  ├─ auth/AuthContext.tsx         会话状态机 + useAuth
│  ├─ routes/RequireAuth.tsx       路由守卫（登录 + 状态 + 角色）
│  ├─ routes/AppRoutes.tsx         路由表
│  ├─ layout/AppLayout.tsx         控制台骨架（侧栏 + 顶栏 + 内容区）
│  ├─ layout/SideNav.tsx           分组菜单
│  ├─ layout/TopBar.tsx            面包屑 + 环境标识 + 用户菜单
│  ├─ pages/LoginPage.tsx          GitHub 登录
│  ├─ pages/LocalLoginPage.tsx     破窗登录
│  ├─ pages/AuthCallbackPage.tsx   回调中转（换 token 后进控制台）
│  ├─ pages/PendingPage.tsx        待审批
│  ├─ pages/RejectedPage.tsx       已拒绝
│  ├─ pages/DashboardPage.tsx      Dashboard 占位
│  ├─ pages/ApprovalsPage.tsx      审批队列
│  ├─ pages/UsersPage.tsx          用户列表（改角色、禁用）
│  └─ test/setup.ts                测试垫片
└─ deploy/nginx/default.conf       生产：静态产物 + /api 反代

deploy/docker-compose.yml          计划 A 已有，Task 10 给 nginx 服务挂上本文件
```

文件职责：`theme/` 只管样式常量，`api/` 只管协议与错误，`auth/` 只管会话状态，
`layout/` 只管外壳，`pages/` 只管页面组装。页面里不直接 `fetch`。

---

### Task 1: 工程骨架与首个渲染测试

**Files:**
- Create: `console-web/package.json`
- Create: `console-web/tsconfig.json`
- Create: `console-web/tsconfig.node.json`
- Create: `console-web/vite.config.ts`
- Create: `console-web/index.html`
- Create: `console-web/src/test/setup.ts`
- Create: `console-web/src/main.tsx`
- Create: `console-web/src/App.tsx`
- Test: `console-web/src/App.test.tsx`

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/App.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the product name', () => {
    render(<App />);

    expect(screen.getByText('信贷风控决策引擎')).toBeInTheDocument();
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm install && npm test
```

预期：失败。首次是 `npm install` 找不到 `package.json`（目录还不存在），
补上 `package.json` 后是 `Cannot find module './App'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/package.json`：

```json
{
  "name": "console-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "preview": "vite preview",
    "typecheck": "tsc --noEmit",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "antd": "5.29.3",
    "react": "18.3.1",
    "react-dom": "18.3.1",
    "react-router-dom": "6.30.6"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "6.10.0",
    "@testing-library/react": "16.3.3",
    "@testing-library/user-event": "14.6.7",
    "@types/react": "18.3.31",
    "@types/react-dom": "18.3.7",
    "@vitejs/plugin-react": "4.7.0",
    "jsdom": "26.1.0",
    "typescript": "5.6.3",
    "vite": "5.4.21",
    "vitest": "2.1.9"
  }
}
```

创建 `console-web/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "isolatedModules": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vitest/globals", "@testing-library/jest-dom", "vite/client"]
  },
  "include": ["src", "vite.config.ts"]
}
```

创建 `console-web/tsconfig.node.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["vite.config.ts"]
}
```

创建 `console-web/vite.config.ts`：

```ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 浏览器只跟 5173 打交道，/api 由 Vite 转给 8080：
    // 这样刷新接口的 Origin 校验（auth.oauth.console-base-url）天然通过。
    proxy: {
      '/api': { target: 'http://localhost:8080' },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
```

创建 `console-web/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>信贷风控决策引擎</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

创建 `console-web/src/test/setup.ts`：

```ts
import '@testing-library/jest-dom/vitest';

// Ant Design 5 的响应式组件会调用 matchMedia 与 ResizeObserver，
// jsdom 两个都没有，不垫的话 render 阶段直接抛异常。
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}
```

创建 `console-web/src/App.tsx`：

```tsx
export function App() {
  return <h1>信贷风控决策引擎</h1>;
}
```

创建 `console-web/src/main.tsx`：

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';

const container = document.getElementById('root');
if (!container) {
  throw new Error('找不到 #root 挂载点');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Test Files 1 passed`、`Tests 1 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 工程骨架与首个渲染测试"
```

---

### Task 2: 设计 token 与主题

**Files:**
- Create: `console-web/src/theme/tokens.ts`
- Create: `console-web/src/theme/antdTheme.ts`
- Create: `console-web/src/styles/global.css`
- Test: `console-web/src/theme/antdTheme.test.ts`

**设计要点：** spec §6.3 的 token 只写在一个文件里，测试断言的就是那份表；
`antdTheme.ts` 只负责翻译，不重新定义颜色。这样换风格时只改 `tokens.ts`。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/theme/antdTheme.test.ts`：

```ts
import { describe, expect, it } from 'vitest';

import { antdThemeConfig } from './antdTheme';
import { tokens } from './tokens';

describe('设计 token', () => {
  it('与 spec §6.3 一致', () => {
    expect(tokens.primary).toBe('#1d4ed8');
    expect(tokens.siderBg).toBe('#0e2a58');
    expect(tokens.controlRadius).toBe(4);
    expect(tokens.cardRadius).toBe(6);
    expect(tokens.textPrimary).toBe('#0f172a');
    expect(tokens.textSecondary).toBe('#64748b');
    expect(tokens.textMuted).toBe('#94a3b8');
    expect(tokens.borderInput).toBe('#d7dfec');
    expect(tokens.borderDivider).toBe('#eef1f6');
    expect(tokens.pageGradient).toBe(
      'linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%)',
    );
  });

  it('Ant Design 主题用 token，不用默认蓝', () => {
    expect(antdThemeConfig.token?.colorPrimary).toBe(tokens.primary);
    expect(antdThemeConfig.token?.colorPrimary).not.toBe('#1677ff');
    expect(antdThemeConfig.token?.borderRadius).toBe(tokens.controlRadius);
    expect(antdThemeConfig.components?.Layout?.siderBg).toBe(tokens.siderBg);
    expect(antdThemeConfig.components?.Menu?.darkItemSelectedBg).toBe(tokens.siderSelected);
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Cannot find module './antdTheme'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/theme/tokens.ts`：

```ts
/**
 * 视觉规范（spec §6.3「风格 A · 稳健金融蓝」）。
 * 全站颜色与圆角的唯一真相，改风格只改这里。
 */
export const tokens = {
  pageGradient: 'linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%)',
  gridLine: 'rgba(255, 255, 255, 0.05)',
  gridSize: '26px',
  cardRadius: 6,
  cardShadow: '0 18px 40px -18px rgba(0, 0, 0, 0.6)',
  cardAccentHeight: 3,
  primary: '#1d4ed8',
  textPrimary: '#0f172a',
  textSecondary: '#64748b',
  textMuted: '#94a3b8',
  borderInput: '#d7dfec',
  borderDivider: '#eef1f6',
  controlRadius: 4,
  siderBg: '#0e2a58',
  siderSelected: '#1d4ed8',
} as const;
```

创建 `console-web/src/theme/antdTheme.ts`：

```ts
import type { ThemeConfig } from 'antd';

import { tokens } from './tokens';

export const antdThemeConfig: ThemeConfig = {
  token: {
    colorPrimary: tokens.primary,
    colorText: tokens.textPrimary,
    colorTextSecondary: tokens.textSecondary,
    colorTextTertiary: tokens.textMuted,
    colorBorder: tokens.borderInput,
    colorSplit: tokens.borderDivider,
    borderRadius: tokens.controlRadius,
  },
  components: {
    Layout: {
      siderBg: tokens.siderBg,
      headerBg: '#ffffff',
      bodyBg: 'transparent',
    },
    Menu: {
      darkItemBg: tokens.siderBg,
      darkSubMenuItemBg: tokens.siderBg,
      darkItemSelectedBg: tokens.siderSelected,
      darkItemColor: 'rgba(255, 255, 255, 0.72)',
      darkItemSelectedColor: '#ffffff',
      itemBorderRadius: tokens.controlRadius,
    },
    Card: {
      borderRadiusLG: tokens.cardRadius,
    },
  },
};
```

创建 `console-web/src/styles/global.css`：

```css
:root {
  --page-gradient: linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%);
  --grid-line: rgba(255, 255, 255, 0.05);
  --text-primary: #0f172a;
}

* {
  box-sizing: border-box;
}

html,
body,
#root {
  height: 100%;
  margin: 0;
}

body {
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', sans-serif;
  color: var(--text-primary);
}

/* 页面底色：深蓝渐变 + 26px 网格线，登录页与待审批页共用 */
.ds-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  background-color: #0e2a58;
  background-image:
    linear-gradient(var(--grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid-line) 1px, transparent 1px),
    var(--page-gradient);
  background-size:
    26px 26px,
    26px 26px,
    100% 100%;
}

/* 卡片：白底、6px 圆角、顶部 3px 强调条 */
.ds-card {
  position: relative;
  width: 100%;
  max-width: 420px;
  padding: 36px 32px 32px;
  background: #ffffff;
  border-radius: 6px;
  box-shadow: 0 18px 40px -18px rgba(0, 0, 0, 0.6);
  overflow: hidden;
}

.ds-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: #1d4ed8;
}

.ds-card--wide {
  max-width: 520px;
}

.ds-card__brand {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.ds-card__subtitle {
  margin: 0 0 28px;
  font-size: 13px;
  color: #64748b;
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：2 个测试文件、3 个测试全部通过。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 设计 token 与 Ant Design 主题（风格 A）"
```

---

### Task 3: API 客户端

**Files:**
- Create: `console-web/src/api/client.ts`
- Test: `console-web/src/api/client.test.ts`

**设计要点：** access token 只存在模块级变量里，不落 `localStorage`——XSS 拿不到持久凭证。
`401` 时先静默续期一次再重试原请求；续期失败才通知上层「会话丢了」。
`renewSession()` 自己**不走** `request()`，否则会递归。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/api/client.test.ts`：

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, renewSession, request, setAccessToken, setSessionLostHandler } from './client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('api client', () => {
  beforeEach(() => {
    setAccessToken(null);
    setSessionLostHandler(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('带上 Bearer 头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { id: 7 }));
    vi.stubGlobal('fetch', fetchMock);
    setAccessToken('token-abc');

    const result = await request<{ id: number }>('/api/me');

    expect(result.id).toBe(7);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/me');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-abc');
  });

  it('把错误响应翻译成带 code 的 ApiError', async () => {
    // 每次调用都要新的 Response：body 只能读一次，复用同一个实例第二次会抛
    // TypeError: Body is unusable。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() =>
        Promise.resolve(jsonResponse(403, { code: 'account_not_active', message: '账号不可用' })),
      ),
    );

    await expect(request('/api/me')).rejects.toMatchObject({
      status: 403,
      code: 'account_not_active',
      message: '账号不可用',
    });
    await expect(request('/api/me')).rejects.toBeInstanceOf(ApiError);
  });

  it('401 时静默续期一次并重试原请求', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 'invalid_token', message: '令牌无效' }))
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh-token' }))
      .mockResolvedValueOnce(jsonResponse(200, { id: 9 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await request<{ id: number }>('/api/me');

    expect(result.id).toBe(9);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh');
    const retryHeaders = fetchMock.mock.calls[2][1].headers as Record<string, string>;
    expect(retryHeaders.Authorization).toBe('Bearer fresh-token');
  });

  it('续期失败时通知上层并抛出原始错误', async () => {
    const sessionLost = vi.fn();
    setSessionLostHandler(sessionLost);
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '请先登录' }))
        .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '请先登录' })),
    );

    await expect(request('/api/me')).rejects.toMatchObject({ status: 401 });
    expect(sessionLost).toHaveBeenCalledTimes(1);
  });

  it('skipRefresh 的请求不做续期', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(401, { code: 'unauthenticated', message: '请先登录' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(request('/api/auth/session', { skipRefresh: true })).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('renewSession 成功后记住新令牌，204 响应不解析 JSON', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh-token' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(renewSession()).resolves.toBe(true);
    await expect(request('/api/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Cannot find module './client'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/api/client.ts`：

```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// access token 只放在内存里：刷新页面即丢，靠 httpOnly Cookie 静默续期。
// 不落 localStorage，XSS 就偷不到持久凭证。
let accessToken: string | null = null;
let sessionLostHandler: (() => void) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setSessionLostHandler(handler: (() => void) | null): void {
  sessionLostHandler = handler;
}

export type RequestOptions = {
  method?: 'GET' | 'POST';
  body?: unknown;
  /** 认证相关请求自己处理 401，不能让 client 再递归续期 */
  skipRefresh?: boolean;
};

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {};
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return fetch(path, {
    method: options.method ?? 'GET',
    headers,
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const payload: unknown = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const body = (payload ?? {}) as { code?: string; message?: string };
    throw new ApiError(
      response.status,
      body.code ?? 'unknown_error',
      body.message ?? `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as T;
}

/**
 * 用 httpOnly Cookie 换新的 access token。
 * 故意不走 request()：否则 401 时会自己调自己。
 */
export async function renewSession(): Promise<boolean> {
  const response = await fetch('/api/auth/refresh', {
    method: 'POST',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    accessToken = null;
    return false;
  }
  const body = (await response.json()) as { accessToken: string };
  accessToken = body.accessToken;
  return true;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options);

  if (response.status === 401 && !options.skipRefresh) {
    if (await renewSession()) {
      response = await send(path, options);
    } else {
      sessionLostHandler?.();
    }
  }

  return parse<T>(response);
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Tests 9 passed`（Task 1 的 1 个 + Task 2 的 2 个 + 本任务 6 个）。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): API 客户端与统一错误契约"
```

---

### Task 4: 会话状态机

**Files:**
- Create: `console-web/src/api/auth.ts`
- Create: `console-web/src/auth/AuthContext.tsx`
- Test: `console-web/src/auth/AuthContext.test.tsx`

**设计要点：** 启动时**先问 `/api/auth/session`**（只读、不消耗刷新链），再按状态决定去向；
只有 `ACTIVE` 才去换 access token。反过来先 `refresh` 的话，`PENDING` 用户会拿到 403，
前端就只能靠错误码猜自己的状态。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/auth/AuthContext.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider, useAuth } from './AuthContext';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function Probe() {
  const { status, user, logout, loginWithLocal } = useAuth();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="user">{user ? `${user.displayName}/${user.role}` : 'none'}</span>
      <button onClick={() => void logout()}>登出</button>
      <button onClick={() => void loginWithLocal('admin', 'pw')}>本地登录</button>
    </div>
  );
}

function renderProbe() {
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
}

describe('AuthProvider', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('PENDING 用户只拿到待审批状态，不去换 access token', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(200, {
          id: 1,
          displayName: 'octocat',
          avatarUrl: null,
          role: 'MEMBER',
          status: 'PENDING',
          appliedAt: null,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);
    renderProbe();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('pending'));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/session');
  });

  it('ACTIVE 用户先查会话再换令牌，并用 /api/me 补全资料', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(200, {
          id: 7,
          displayName: 'octocat',
          avatarUrl: null,
          role: 'ADMIN',
          status: 'ACTIVE',
          appliedAt: null,
        }),
      )
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh' }))
      .mockResolvedValueOnce(
        jsonResponse(200, {
          id: 7,
          displayName: 'octocat',
          email: 'o@example.com',
          avatarUrl: null,
          role: 'ADMIN',
          status: 'ACTIVE',
          breakGlass: false,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);
    renderProbe();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('active'));
    expect(screen.getByTestId('user')).toHaveTextContent('octocat/ADMIN');
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/auth/session',
      '/api/auth/refresh',
      '/api/me',
    ]);
  });

  it('没有 Cookie 时是匿名', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' })),
    );
    renderProbe();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));
  });

  it('登出后回到匿名', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    renderProbe();
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));

    await userEvent.click(screen.getByRole('button', { name: '登出' }));

    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/logout');
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));
  });

  it('本地登录成功后进入 ACTIVE', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' }))
      .mockResolvedValueOnce(
        jsonResponse(200, { accessToken: 'local-token', expiresInSeconds: 900, role: 'ADMIN', status: 'ACTIVE' }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {
          id: 3,
          displayName: 'break-glass-admin',
          email: null,
          avatarUrl: null,
          role: 'ADMIN',
          status: 'ACTIVE',
          breakGlass: true,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);
    renderProbe();
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));

    await userEvent.click(screen.getByRole('button', { name: '本地登录' }));

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('active'));
    expect(screen.getByTestId('user')).toHaveTextContent('break-glass-admin/ADMIN');
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Cannot find module './AuthContext'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/api/auth.ts`：

```ts
import { request } from './client';

export type Role = 'ADMIN' | 'MEMBER' | 'STRATEGIST' | 'VIEWER';
export type UserStatus = 'PENDING' | 'ACTIVE' | 'DISABLED' | 'REJECTED';

export type SessionResponse = {
  id: number;
  displayName: string | null;
  avatarUrl: string | null;
  role: Role;
  status: UserStatus;
  appliedAt: string | null;
};

export type CurrentUser = {
  id: number;
  displayName: string;
  email: string;
  avatarUrl: string;
  role: Role;
  status: UserStatus;
  breakGlass: boolean;
};

export type TokenResponse = {
  accessToken: string;
  expiresInSeconds: number;
  role: Role;
  status: UserStatus;
};

export function fetchSession(): Promise<SessionResponse> {
  return request<SessionResponse>('/api/auth/session', { skipRefresh: true });
}

export function fetchCurrentUser(): Promise<CurrentUser> {
  return request<CurrentUser>('/api/me');
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST', skipRefresh: true });
}

export function loginWithLocal(username: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>('/api/auth/local/login', {
    method: 'POST',
    body: { username, password },
    skipRefresh: true,
  });
}

/** GitHub 登录是整页跳转，不是 fetch：要离开 SPA 去 GitHub。 */
export function goToGitHubAuthorize(): void {
  window.location.assign('/api/auth/github/authorize');
}
```

创建 `console-web/src/auth/AuthContext.tsx`：

```tsx
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';

import {
  fetchCurrentUser,
  fetchSession,
  goToGitHubAuthorize,
  loginWithLocal as loginWithLocalRequest,
  logout as logoutRequest,
} from '../api/auth';
import type { CurrentUser, Role } from '../api/auth';
import { ApiError, renewSession, setAccessToken, setSessionLostHandler } from '../api/client';

// 会话状态对外一律小写：AuthContext 用 session.status.toLowerCase() 落状态，
// RequireAuth 也只比较小写，写成 UserStatus 原样（大写）会让守卫分支永远不成立。
export type SessionStatus = 'loading' | 'anonymous' | 'active' | 'pending' | 'rejected' | 'disabled';

type AuthContextValue = {
  status: SessionStatus;
  user: CurrentUser | null;
  loginWithGitHub: () => void;
  completeLogin: () => Promise<void>;
  loginWithLocal: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [user, setUser] = useState<CurrentUser | null>(null);
  const bootstrapped = useRef(false);

  const forgetSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    setStatus('anonymous');
  }, []);

  useEffect(() => {
    setSessionLostHandler(forgetSession);
    return () => setSessionLostHandler(null);
  }, [forgetSession]);

  useEffect(() => {
    if (bootstrapped.current) {
      return;
    }
    bootstrapped.current = true;

    void (async () => {
      try {
        const session = await fetchSession();
        if (session.status !== 'ACTIVE') {
          // PENDING / REJECTED / DISABLED 不需要 access token：
          // 它们的页面只用会话信息就能渲染，别去消耗刷新链。
          setStatus(session.status.toLowerCase() as SessionStatus);
          return;
        }
        if (!(await renewSession())) {
          forgetSession();
          return;
        }
        setUser(await fetchCurrentUser());
        setStatus('active');
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          forgetSession();
          return;
        }
        forgetSession();
      }
    })();
  }, [forgetSession]);

  const completeLogin = useCallback(async () => {
    if (!(await renewSession())) {
      forgetSession();
      throw new ApiError(401, 'unauthenticated', '登录未完成，请重新登录');
    }
    setUser(await fetchCurrentUser());
    setStatus('active');
  }, [forgetSession]);

  const loginWithLocal = useCallback(
    async (username: string, password: string) => {
      const token = await loginWithLocalRequest(username, password);
      setAccessToken(token.accessToken);
      setUser(await fetchCurrentUser());
      setStatus('active');
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      forgetSession();
    }
  }, [forgetSession]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, loginWithGitHub: goToGitHubAuthorize, completeLogin, loginWithLocal, logout }),
    [status, user, completeLogin, loginWithLocal, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return value;
}

export type { Role };
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Tests 14 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 会话状态机与启动引导"
```

---

### Task 5: 路由与守卫

**Files:**
- Create: `console-web/src/routes/RequireAuth.tsx`
- Create: `console-web/src/routes/AppRoutes.tsx`
- Modify: `console-web/src/App.tsx`（接上 ConfigProvider、AuthProvider、BrowserRouter、路由表）
- Modify: `console-web/src/App.test.tsx`（改成断言骨架文案，并补守卫测试）
- Test: `console-web/src/routes/AppRoutes.test.tsx`

**设计要点：** 会话校验期间渲染占位文案，**不闪白屏**（spec §6.2）。
页面级组件在 Task 6/7 才实现，本任务先用最小占位页把路由与守卫跑通。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/routes/AppRoutes.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthContext';
import { AppRoutes } from './AppRoutes';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function session(status: string, role: string): Response {
  return jsonResponse(200, { id: 1, displayName: 'octocat', avatarUrl: null, role, status, appliedAt: null });
}

function renderAt(path: string) {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('路由守卫', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('会话校验期间显示占位，不闪白屏', () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})));

    renderAt('/');

    expect(screen.getByText('正在校验会话…')).toBeInTheDocument();
  });

  it('未登录访问控制台会被送到登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' })),
    );

    renderAt('/');

    await waitFor(() => expect(screen.getByRole('button', { name: /使用 GitHub 登录/ })).toBeInTheDocument());
  });

  it('待审批用户访问控制台会被送到待审批页', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(session('PENDING', 'MEMBER')));

    renderAt('/');

    await waitFor(() => expect(screen.getByRole('heading', { name: '等待审批' })).toBeInTheDocument());
  });

  it('非管理员访问管理页会被送回 Dashboard', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(session('ACTIVE', 'MEMBER'))
        .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh' }))
        .mockResolvedValueOnce(
          jsonResponse(200, {
            id: 1,
            displayName: 'octocat',
            email: null,
            avatarUrl: null,
            role: 'MEMBER',
            status: 'ACTIVE',
            breakGlass: false,
          }),
        ),
    );

    renderAt('/admin/approvals');

    // Dashboard 是 <Card title="概览">，标题不是 heading；
    // 而且顶栏面包屑也有「概览」，所以断言卡片正文这句独有的文案
    await waitFor(() => expect(screen.getByText(/决策流、规则与监控尚未接入/)).toBeInTheDocument());
  });

  it('被停用用户访问控制台会被送到登录页', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(session('DISABLED', 'MEMBER')));

    renderAt('/');

    await waitFor(() => expect(screen.getByRole('button', { name: /使用 GitHub 登录/ })).toBeInTheDocument());
  });

  it('管理员能进入审批队列', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(session('ACTIVE', 'ADMIN'))
        .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh' }))
        .mockResolvedValueOnce(
          jsonResponse(200, {
            id: 1,
            displayName: 'octocat',
            email: null,
            avatarUrl: null,
            role: 'ADMIN',
            status: 'ACTIVE',
            breakGlass: false,
          }),
        )
        .mockResolvedValueOnce(jsonResponse(200, [])),
    );

    renderAt('/admin/approvals');

    // ApprovalsPage 现在是 <Card title="审批队列">，卡片标题不是 heading，
    // 而且侧栏也有同名菜单项，所以断言它加载完成后的空态文案
    await waitFor(() => expect(screen.getByText('当前没有待审批的申请')).toBeInTheDocument());
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Cannot find module './AppRoutes'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/routes/RequireAuth.tsx`：

```tsx
import { Navigate } from 'react-router-dom';
import type { ReactElement } from 'react';

import { useAuth } from '../auth/AuthContext';
import type { SessionStatus } from '../auth/AuthContext';

type Props = {
  /** 允许进入的会话状态，默认只允许 ACTIVE */
  allow?: SessionStatus[];
  /** 额外的角色要求 */
  role?: string;
  children: ReactElement;
};

export function RequireAuth({ allow = ['active'], role, children }: Props) {
  const { status, user } = useAuth();

  if (status === 'loading') {
    return <div style={{ padding: 48, textAlign: 'center', color: '#64748b' }}>正在校验会话…</div>;
  }
  if (status === 'anonymous' || status === 'disabled') {
    return <Navigate to="/login" replace />;
  }
  if (status === 'pending') {
    return allow.includes('pending') ? children : <Navigate to="/pending" replace />;
  }
  if (status === 'rejected') {
    return allow.includes('rejected') ? children : <Navigate to="/rejected" replace />;
  }
  if (!allow.includes('active')) {
    return <Navigate to="/" replace />;
  }
  if (role && user?.role !== role) {
    return <Navigate to="/" replace />;
  }
  return children;
}
```

创建 `console-web/src/routes/AppRoutes.tsx`：

```tsx
import { Navigate, Route, Routes } from 'react-router-dom';

import { RequireAuth } from './RequireAuth';
import { AppLayout } from '../layout/AppLayout';
import { ApprovalsPage } from '../pages/ApprovalsPage';
import { AuthCallbackPage } from '../pages/AuthCallbackPage';
import { DashboardPage } from '../pages/DashboardPage';
import { LocalLoginPage } from '../pages/LocalLoginPage';
import { LoginPage } from '../pages/LoginPage';
import { PendingPage } from '../pages/PendingPage';
import { RejectedPage } from '../pages/RejectedPage';
import { UsersPage } from '../pages/UsersPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/login/local" element={<LocalLoginPage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />

      <Route
        path="/pending"
        element={
          <RequireAuth allow={['pending']}>
            <PendingPage />
          </RequireAuth>
        }
      />
      <Route
        path="/rejected"
        element={
          <RequireAuth allow={['rejected']}>
            <RejectedPage />
          </RequireAuth>
        }
      />

      <Route
        path="/"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route
          path="admin/approvals"
          element={
            <RequireAuth role="ADMIN">
              <ApprovalsPage />
            </RequireAuth>
          }
        />
        <Route
          path="admin/users"
          element={
            <RequireAuth role="ADMIN">
              <UsersPage />
            </RequireAuth>
          }
        />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
```

把 `console-web/src/App.tsx` 覆盖为：

```tsx
import { ConfigProvider } from 'antd';
import { BrowserRouter } from 'react-router-dom';

import { AuthProvider } from './auth/AuthContext';
import { AppRoutes } from './routes/AppRoutes';
import { antdThemeConfig } from './theme/antdTheme';
import './styles/global.css';

export function App() {
  return (
    <ConfigProvider theme={antdThemeConfig}>
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}
```

把 `console-web/src/App.test.tsx` 覆盖为：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('挂载后进入登录页，并带上产品名', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 'unauthenticated', message: '缺少会话' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    render(<App />);

    await waitFor(() => expect(screen.getByRole('button', { name: /使用 GitHub 登录/ })).toBeInTheDocument());
    expect(screen.getByText('信贷风控决策引擎')).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
```

本任务先用占位页面把这 8 个页面模块建出来（Task 6/7/8/9 会逐个替换成真实实现）：

```bash
cd console-web/src/pages
for p in LoginPage LocalLoginPage AuthCallbackPage PendingPage RejectedPage DashboardPage ApprovalsPage UsersPage; do
  cat > "$p.tsx" <<EOF
export function $p() {
  return null;
}
EOF
done
```

同时创建 `console-web/src/layout/AppLayout.tsx` 占位：

```tsx
import { Outlet } from 'react-router-dom';

export function AppLayout() {
  return <Outlet />;
}
```

再把 Task 5 要求的两个最小可实现补上——登录页与待审批页（其余留在后续任务）：

`console-web/src/pages/LoginPage.tsx`（`App.test.tsx` 要断言产品名，占位版也得带上它）：

```tsx
export function LoginPage() {
  return (
    <>
      <p>信贷风控决策引擎</p>
      <h1>登录</h1>
    </>
  );
}
```

`console-web/src/pages/PendingPage.tsx`：

```tsx
export function PendingPage() {
  return <h1>等待审批</h1>;
}
```

`console-web/src/pages/DashboardPage.tsx`：

```tsx
export function DashboardPage() {
  return <h1>Dashboard</h1>;
}
```

`console-web/src/pages/ApprovalsPage.tsx`：

```tsx
export function ApprovalsPage() {
  return <h1>审批队列</h1>;
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test && npm run typecheck
```

预期：`Tests 20 passed`，且 `npm run typecheck` 无输出——
本任务的类型错误（`SessionStatus` 写成大写枚举）只有 typecheck 抓得到，
`npm test` 因为运行时值是小写反而全绿，所以这一步必须带上 typecheck。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 路由表与登录/状态守卫"
```

---

### Task 6: 登录页与破窗登录页

**Files:**
- Modify: `console-web/src/pages/LoginPage.tsx`（替换占位）
- Modify: `console-web/src/pages/LocalLoginPage.tsx`（替换占位）
- Modify: `console-web/src/App.test.tsx`、`console-web/src/routes/AppRoutes.test.tsx`
  （真实登录页没有 `h1 登录`，两处断言改成认 GitHub 按钮）
- Test: `console-web/src/pages/LoginPage.test.tsx`
- Test: `console-web/src/pages/LocalLoginPage.test.tsx`

**设计要点：** 登录页只暴露 GitHub 一个入口；破窗入口 `/login/local` 在导航与页面上都不出现，
只有知道路径的人能进（spec §6.1）。后端所有回调错误都用 `?error=` 传回来，这里做一次文案映射。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/pages/LoginPage.test.tsx`：

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthContext';
import { LoginPage } from './LoginPage';

function renderLogin(path = '/login') {
  // LoginPage 内部用 useAuth()，不包 AuthProvider 会直接抛错。
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <LoginPage />
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('LoginPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('点 GitHub 按钮就整页跳转到后端授权入口', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, href: '/login' });

    renderLogin();
    await userEvent.click(screen.getByRole('button', { name: /使用 GitHub 登录/ }));

    expect(assign).toHaveBeenCalledWith('/api/auth/github/authorize');
  });

  it('把后端的 error 参数翻译成人话', () => {
    renderLogin('/login?error=state_expired');

    expect(screen.getByText('登录会话已过期，请重新发起登录。')).toBeInTheDocument();
  });

  it('未知错误码也给得出提示', () => {
    renderLogin('/login?error=whatever');

    expect(screen.getByText('登录失败，请重试。')).toBeInTheDocument();
  });

  it('页面上不出现破窗入口', () => {
    renderLogin();

    expect(screen.queryByText(/破窗/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /本地/ })).not.toBeInTheDocument();
  });
});
```

创建 `console-web/src/pages/LocalLoginPage.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthContext';
import { LocalLoginPage } from './LocalLoginPage';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderLocalLogin() {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/login/local']}>
        <Routes>
          <Route path="/login/local" element={<LocalLoginPage />} />
          <Route path="/" element={<h1>控制台首页</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('LocalLoginPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('登录成功后进入控制台', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' }))
        .mockResolvedValueOnce(
          jsonResponse(200, { accessToken: 't', expiresInSeconds: 900, role: 'ADMIN', status: 'ACTIVE' }),
        )
        .mockResolvedValueOnce(
          jsonResponse(200, {
            id: 1,
            displayName: 'break-glass-admin',
            email: null,
            avatarUrl: null,
            role: 'ADMIN',
            status: 'ACTIVE',
            breakGlass: true,
          }),
        ),
    );
    renderLocalLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'break-glass-admin');
    await userEvent.type(screen.getByLabelText('密码'), 'password');
    // antd 会给两个汉字之间插空格（"登 录"），所以用正则匹配
    await userEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => expect(screen.getByRole('heading', { name: '控制台首页' })).toBeInTheDocument());
  });

  it('凭据错误时显示后端返回的原因，不跳转', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' }))
        .mockResolvedValueOnce(jsonResponse(401, { code: 'invalid_credentials', message: '用户名或密码不正确' })),
    );
    renderLocalLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'admin');
    await userEvent.type(screen.getByLabelText('密码'), 'wrong');
    // antd 会给两个汉字之间插空格（"登 录"），所以用正则匹配
    await userEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    expect(await screen.findByText('用户名或密码不正确')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '控制台首页' })).not.toBeInTheDocument();
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Unable to find a role="button" with name /使用 GitHub 登录/`（页面还是占位实现）。

- [x] **Step 3: 写最小实现**

把 `console-web/src/pages/LoginPage.tsx` 覆盖为：

```tsx
import { InfoCircleOutlined } from '@ant-design/icons';
import { Alert, Button } from 'antd';
import { useSearchParams } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

const ERROR_MESSAGES: Record<string, string> = {
  oauth_failed: 'GitHub 授权失败，请重试。',
  state_expired: '登录会话已过期，请重新发起登录。',
  disabled: '账号已被停用，请联系管理员。',
  provider_not_configured: '服务端尚未配置 GitHub 登录凭据，请联系管理员。',
};

export function LoginPage() {
  const { loginWithGitHub } = useAuth();
  const [params] = useSearchParams();
  const error = params.get('error');

  return (
    <div className="ds-page">
      <div className="ds-card">
        <p className="ds-card__brand">信贷风控决策引擎</p>
        <p className="ds-card__subtitle">内部研发与策略人员专用</p>

        {error ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 20 }}
            message={ERROR_MESSAGES[error] ?? '登录失败，请重试。'}
          />
        ) : null}

        <Button type="primary" size="large" block onClick={loginWithGitHub}>
          使用 GitHub 登录
        </Button>

        <p style={{ marginTop: 20, marginBottom: 0, fontSize: 12, color: '#94a3b8' }}>
          <InfoCircleOutlined style={{ marginRight: 6 }} />
          首次登录需要管理员审批，通过后即可进入控制台。
        </p>
      </div>
    </div>
  );
}
```

把 `console-web/src/pages/LocalLoginPage.tsx` 覆盖为：

```tsx
import { Alert, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

type FormValues = { username: string; password: string };

export function LocalLoginPage() {
  const { loginWithLocal } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: FormValues) => {
    setSubmitting(true);
    setError(null);
    try {
      await loginWithLocal(values.username, values.password);
      navigate('/', { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'too_many_attempts') {
        setError('尝试次数过多，请稍后再试。');
      } else if (caught instanceof ApiError) {
        setError(caught.message);
      } else {
        setError('登录失败，请重试。');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="ds-page">
      <div className="ds-card">
        <p className="ds-card__brand">破窗登录</p>
        <p className="ds-card__subtitle">仅供 GitHub 不可用时使用，账号由环境变量下发</p>

        {error ? <Alert type="error" showIcon style={{ marginBottom: 20 }} message={error} /> : null}

        <Form<FormValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input autoComplete="username" size="large" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" size="large" />
          </Form.Item>
          <Button type="primary" size="large" block htmlType="submit" loading={submitting}>
            登录
          </Button>
        </Form>
      </div>
    </div>
  );
}
```

本任务新增依赖 `@ant-design/icons`，在 `console-web/package.json` 的 `dependencies` 里加：

```json
    "@ant-design/icons": "5.6.1",
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm install && npm test
```

预期：`Tests 26 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): GitHub 登录页与破窗登录页"
```

---

### Task 7: 待审批、已拒绝与回调中转页

**Files:**
- Modify: `console-web/src/pages/PendingPage.tsx`
- Modify: `console-web/src/pages/RejectedPage.tsx`
- Modify: `console-web/src/pages/AuthCallbackPage.tsx`
- Test: `console-web/src/pages/StatusPages.test.tsx`

**设计要点：** `/auth/callback` 是后端 GitHub 回调后落地的地址：此时 Cookie 已经写好，
页面负责「用 Cookie 换 access token → 拉资料 → 进控制台」。这一步失败就退回登录页并带 `error`。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/pages/StatusPages.test.tsx`：

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthContext';
import { AuthCallbackPage } from './AuthCallbackPage';
import { PendingPage } from './PendingPage';
import { RejectedPage } from './RejectedPage';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function session(status: string) {
  return jsonResponse(200, { id: 1, displayName: 'octocat', avatarUrl: null, role: 'MEMBER', status, appliedAt: null });
}

function renderAt(path: string, element: React.ReactElement) {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={path} element={element} />
          <Route path="/" element={<h1>控制台首页</h1>} />
          <Route path="/login" element={<h1>登录页</h1>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('状态页', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('待审批页显示申请时间并可登出', async () => {
    const fetchMock = vi.fn().mockResolvedValue(session('PENDING'));
    vi.stubGlobal('fetch', fetchMock);
    renderAt('/pending', <PendingPage />);

    expect(await screen.findByRole('heading', { name: '等待审批' })).toBeInTheDocument();

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await userEvent.click(screen.getByRole('button', { name: '退出登录' }));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('/api/auth/logout', expect.anything()));
  });

  it('已拒绝页说明原因与下一步', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(session('REJECTED')));
    renderAt('/rejected', <RejectedPage />);

    expect(await screen.findByRole('heading', { name: '申请未通过' })).toBeInTheDocument();
    expect(screen.getByText(/联系管理员/)).toBeInTheDocument();
  });

  it('回调中转页换到令牌后进入控制台', async () => {
    // 这里挂载了 AuthProvider，它自己也会打 /api/auth/session 与 /api/auth/refresh，
    // 而且子组件的 effect 先于父组件执行——回调页的 refresh 排在 bootstrap 之前。
    // 按 URL 分发响应才不依赖调用顺序（用 mockResolvedValueOnce 会错位）。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) => {
        if (url === '/api/auth/session') {
          return Promise.resolve(session('ACTIVE'));
        }
        if (url === '/api/auth/refresh') {
          return Promise.resolve(jsonResponse(200, { accessToken: 'fresh' }));
        }
        if (url === '/api/me') {
          return Promise.resolve(
            jsonResponse(200, {
              id: 1,
              displayName: 'octocat',
              email: null,
              avatarUrl: null,
              role: 'MEMBER',
              status: 'ACTIVE',
              breakGlass: false,
            }),
          );
        }
        return Promise.reject(new Error(`未预期的请求：${url}`));
      }),
    );
    renderAt('/auth/callback', <AuthCallbackPage />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '控制台首页' })).toBeInTheDocument());
  });

  it('回调中转页换不到令牌就退回登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(jsonResponse(401, { code: 'unauthenticated', message: '缺少会话' })),
    );
    renderAt('/auth/callback', <AuthCallbackPage />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '登录页' })).toBeInTheDocument());
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Unable to find a role="button" with name "退出登录"`（页面仍是占位）。

- [x] **Step 3: 写最小实现**

把 `console-web/src/pages/PendingPage.tsx` 覆盖为：

```tsx
import { ClockCircleOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function PendingPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="ds-page">
      <div className="ds-card ds-card--wide" style={{ textAlign: 'center' }}>
        <ClockCircleOutlined style={{ fontSize: 32, color: '#1d4ed8' }} />
        <h1 style={{ marginTop: 16, fontSize: 20 }}>等待审批</h1>
        <p style={{ color: '#64748b', fontSize: 13, lineHeight: 1.9 }}>
          {user?.displayName ? `${user.displayName}，` : ''}
          你的账号已提交，管理员审批通过后即可进入控制台。
          <br />
          审批结果在下次登录时生效。
        </p>
        <Button onClick={() => void onLogout()}>退出登录</Button>
      </div>
    </div>
  );
}
```

把 `console-web/src/pages/RejectedPage.tsx` 覆盖为：

```tsx
import { CloseCircleOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function RejectedPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="ds-page">
      <div className="ds-card ds-card--wide" style={{ textAlign: 'center' }}>
        <CloseCircleOutlined style={{ fontSize: 32, color: '#cf1322' }} />
        <h1 style={{ marginTop: 16, fontSize: 20 }}>申请未通过</h1>
        <p style={{ color: '#64748b', fontSize: 13, lineHeight: 1.9 }}>
          本次访问申请未通过。如果认为这是误判，请联系管理员核实身份后重新提交。
        </p>
        <Button onClick={() => void onLogout()}>退出登录</Button>
      </div>
    </div>
  );
}
```

把 `console-web/src/pages/AuthCallbackPage.tsx` 覆盖为：

```tsx
import { Spin } from 'antd';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function AuthCallbackPage() {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;

    void (async () => {
      try {
        await completeLogin();
        navigate('/', { replace: true });
      } catch {
        navigate('/login?error=oauth_failed', { replace: true });
      }
    })();
  }, [completeLogin, navigate]);

  return (
    <div className="ds-page">
      <div className="ds-card" style={{ textAlign: 'center' }}>
        <Spin size="large" />
        <p style={{ marginTop: 20, marginBottom: 0, color: '#64748b', fontSize: 13 }}>正在登录…</p>
      </div>
    </div>
  );
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Tests 30 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 待审批、已拒绝与回调中转页"
```

---

### Task 8: 控制台骨架

**Files:**
- Create: `console-web/src/layout/EnvBadge.tsx`
- Create: `console-web/src/layout/SideNav.tsx`
- Create: `console-web/src/layout/TopBar.tsx`
- Modify: `console-web/src/layout/AppLayout.tsx`（替换占位）
- Modify: `console-web/src/pages/DashboardPage.tsx`（替换占位）
- Test: `console-web/src/layout/AppLayout.test.tsx`

**设计要点：** 环境标识必须一眼可辨（spec §6.2）：`dev` 橙色、`prod` 深红，
取 `VITE_APP_ENV`，缺省按 `dev` 处理——宁可把生产误标成开发，也不要反过来。
除「系统管理 → 审批 / 用户」外的菜单项一律 `disabled`，点了不跳转，避免假装功能已存在。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/layout/AppLayout.test.tsx`：

```tsx
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../auth/AuthContext';
import { AppLayout } from './AppLayout';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubActiveAdmin() {
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      jsonResponse(200, {
        id: 1,
        displayName: 'octocat',
        avatarUrl: null,
        role: 'ADMIN',
        status: 'ACTIVE',
        appliedAt: null,
      }),
    )
    .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'fresh' }))
    .mockResolvedValueOnce(
      jsonResponse(200, {
        id: 1,
        displayName: 'octocat',
        email: null,
        avatarUrl: null,
        role: 'ADMIN',
        status: 'ACTIVE',
        breakGlass: false,
      }),
    );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderLayout(path = '/') {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<AppLayout />}>
            <Route index element={<h1>概览内容</h1>} />
            <Route path="admin/approvals" element={<h1>审批队列</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

describe('AppLayout', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('渲染分组导航、环境标识与用户信息', async () => {
    stubActiveAdmin();
    renderLayout();

    await waitFor(() => expect(screen.getByText('概览内容')).toBeInTheDocument());
    expect(screen.getByText('决策中心')).toBeInTheDocument();
    expect(screen.getByText('系统管理')).toBeInTheDocument();
    expect(screen.getByText('开发环境')).toBeInTheDocument();
    expect(screen.getByText('octocat')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
  });

  it('生产环境用红色标识', async () => {
    stubActiveAdmin();
    vi.stubEnv('VITE_APP_ENV', 'prod');
    renderLayout();

    await waitFor(() => expect(screen.getByText('生产环境')).toBeInTheDocument());
  });

  it('未实现的菜单项不可点', async () => {
    stubActiveAdmin();
    renderLayout();

    await waitFor(() => expect(screen.getByText('概览内容')).toBeInTheDocument());
    // 「规则中心」是分组标签的 <li>，本身没有 aria-disabled，
    // 被禁用的是组里的菜单项。
    const placeholder = screen.getByText('规则与策略').closest('li');
    expect(placeholder).toHaveAttribute('aria-disabled', 'true');
  });

  it('面包屑跟随路由', async () => {
    stubActiveAdmin();
    renderLayout('/admin/approvals');

    // 侧栏菜单项也叫「审批队列」，所以只在面包屑内部断言
    const breadcrumb = await screen.findByRole('navigation', { name: '面包屑' });
    expect(within(breadcrumb).getByText('系统管理')).toBeInTheDocument();
    expect(within(breadcrumb).getByText('审批队列')).toBeInTheDocument();
  });

  it('用户菜单里的登出会调后端', async () => {
    const fetchMock = stubActiveAdmin();
    renderLayout();
    await waitFor(() => expect(screen.getByText('octocat')).toBeInTheDocument());

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await userEvent.click(screen.getByText('octocat'));
    await userEvent.click(await screen.findByText('退出登录'));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenLastCalledWith('/api/auth/logout', expect.anything()),
    );
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Unable to find an element with the text: 决策中心`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/layout/EnvBadge.tsx`：

```tsx
import { Tag } from 'antd';

/** 环境标识取构建期注入的 VITE_APP_ENV，缺省按 dev 处理。 */
export function currentEnv(): string {
  return import.meta.env.VITE_APP_ENV ?? 'dev';
}

export function EnvBadge() {
  const env = currentEnv();
  const isProd = env === 'prod';

  return (
    <Tag color={isProd ? '#a8071a' : '#d46b08'} style={{ marginInlineEnd: 0, fontWeight: 600 }}>
      {isProd ? '生产环境' : '开发环境'}
    </Tag>
  );
}
```

创建 `console-web/src/layout/SideNav.tsx`：

```tsx
import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FundOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Menu } from 'antd';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';

type MenuItem = Required<MenuProps>['items'][number];

// 本次只通「系统管理 → 审批 / 用户」，其余是占位，一律 disabled：
// 点了没反应好过点了进空页面。
const ITEMS: MenuItem[] = [
  {
    key: 'grp-decision',
    label: '决策中心',
    type: 'group',
    children: [
      { key: 'decision-flow', label: '决策流编排', icon: <ApartmentOutlined />, disabled: true },
      { key: 'decision-run', label: '决策执行', icon: <DeploymentUnitOutlined />, disabled: true },
    ],
  },
  {
    key: 'grp-rule',
    label: '规则中心',
    type: 'group',
    children: [
      { key: 'rule-list', label: '规则与策略', icon: <AuditOutlined />, disabled: true },
      { key: 'variable', label: '变量与数据接入', icon: <FundOutlined />, disabled: true },
    ],
  },
  {
    key: 'grp-monitor',
    label: '监控中心',
    type: 'group',
    children: [{ key: 'monitor-dashboard', label: '执行监控', icon: <DashboardOutlined />, disabled: true }],
  },
  {
    key: 'grp-system',
    label: '系统管理',
    type: 'group',
    children: [
      { key: '/admin/approvals', label: '审批队列', icon: <AuditOutlined /> },
      { key: '/admin/users', label: '用户管理', icon: <TeamOutlined /> },
    ],
  },
];

export function SideNav() {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (
    <Menu
      theme="dark"
      mode="inline"
      selectedKeys={[pathname]}
      items={ITEMS}
      onClick={({ key }) => navigate(key)}
      style={{ borderInlineEnd: 'none' }}
    />
  );
}
```

创建 `console-web/src/layout/TopBar.tsx`：

```tsx
import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Breadcrumb, Button, Dropdown, Layout, Space, Tag } from 'antd';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';
import { EnvBadge } from './EnvBadge';

const CRUMBS: Record<string, string[]> = {
  '/': ['概览'],
  '/admin/approvals': ['系统管理', '审批队列'],
  '/admin/users': ['系统管理', '用户管理'],
};

export function TopBar() {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const trail = CRUMBS[pathname] ?? ['概览'];

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <Layout.Header
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingInline: 24,
        borderBottom: '1px solid #eef1f6',
      }}
    >
      <Breadcrumb
        aria-label="面包屑"
        items={[
          { title: <Link to="/">首页</Link> },
          ...trail.map((label) => ({ title: label })),
        ]}
      />

      <Space size={16}>
        <EnvBadge />
        <Dropdown
          menu={{
            items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
            onClick: ({ key }) => {
              if (key === 'logout') {
                void onLogout();
              }
            },
          }}
        >
          <Button type="text" style={{ height: 40, paddingInline: 8 }}>
            <Space size={8}>
              <Avatar size={28} src={user?.avatarUrl || undefined} icon={<UserOutlined />} />
              <span>{user?.displayName ?? '未命名'}</span>
              <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                {user?.role ?? '-'}
              </Tag>
            </Space>
          </Button>
        </Dropdown>
      </Space>
    </Layout.Header>
  );
}
```

把 `console-web/src/layout/AppLayout.tsx` 覆盖为：

```tsx
import { Layout } from 'antd';
import { Outlet } from 'react-router-dom';

import { SideNav } from './SideNav';
import { TopBar } from './TopBar';
import { tokens } from '../theme/tokens';

export function AppLayout() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider width={216} style={{ background: tokens.siderBg }}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            paddingInline: 20,
            color: '#ffffff',
            fontWeight: 600,
            letterSpacing: 0.5,
          }}
        >
          风控决策引擎
        </div>
        <SideNav />
      </Layout.Sider>
      <Layout>
        <TopBar />
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
```

把 `console-web/src/pages/DashboardPage.tsx` 覆盖为：

```tsx
import { Card } from 'antd';

export function DashboardPage() {
  return (
    <Card title="概览">
      <p style={{ margin: 0, color: '#64748b' }}>
        决策流、规则与监控尚未接入。当前可用的功能在「系统管理」下：审批队列与用户管理。
      </p>
    </Card>
  );
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Tests 35 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 控制台骨架（侧栏、顶栏、环境标识）"
```

---

### Task 9: 审批队列与用户管理

**Files:**
- Create: `console-web/src/api/admin.ts`
- Modify: `console-web/src/pages/ApprovalsPage.tsx`（替换占位）
- Modify: `console-web/src/pages/UsersPage.tsx`（替换占位）
- Test: `console-web/src/pages/ApprovalsPage.test.tsx`
- Test: `console-web/src/pages/UsersPage.test.tsx`

**设计要点：** 错误就地用 `Alert` 显示（`role="alert"`），不用全局 toast——
全局 toast 在测试里难断言，而且管理员在批量操作时更需要「错在哪一行」的上下文。
审批通过时把角色选在行内，一次动作完成「通过 + 赋角色」（spec §5.3）。

- [x] **Step 1: 先写失败的测试**

创建 `console-web/src/pages/ApprovalsPage.test.tsx`：

```tsx
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApprovalsPage } from './ApprovalsPage';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const PENDING = [
  {
    id: 2,
    displayName: 'octocat',
    email: 'octocat@example.com',
    avatarUrl: null,
    role: 'MEMBER',
    status: 'PENDING',
    createdAt: '2026-10-07T10:00:00Z',
    lastLoginAt: null,
  },
];

describe('ApprovalsPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('列出待审批用户，通过时带上选中的角色', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, PENDING))
      .mockResolvedValueOnce(jsonResponse(200, { ...PENDING[0], status: 'ACTIVE' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<ApprovalsPage />);

    expect(await screen.findByText('octocat')).toBeInTheDocument();

    const row = screen.getByText('octocat').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: /通\s*过/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe('/api/admin/users/2/approve');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ role: 'MEMBER' });
    await waitFor(() => expect(screen.queryByText('octocat')).not.toBeInTheDocument());
  });

  it('拒绝会调拒绝接口', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, PENDING))
      .mockResolvedValueOnce(jsonResponse(200, { ...PENDING[0], status: 'REJECTED' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<ApprovalsPage />);
    await screen.findByText('octocat');
    await userEvent.click(screen.getByRole('button', { name: /拒\s*绝/ }));

    await waitFor(() => expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/users/2/reject'));
  });

  it('后端业务错误就地显示', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(200, PENDING))
        .mockResolvedValueOnce(jsonResponse(400, { code: 'cannot_modify_self', message: '不能审批自己' })),
    );

    render(<ApprovalsPage />);
    await screen.findByText('octocat');
    await userEvent.click(screen.getByRole('button', { name: /通\s*过/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('不能审批自己');
  });

  it('空队列给明确提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, [])));

    render(<ApprovalsPage />);

    expect(await screen.findByText('当前没有待审批的申请')).toBeInTheDocument();
  });
});
```

创建 `console-web/src/pages/UsersPage.test.tsx`：

```tsx
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UsersPage } from './UsersPage';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const ACTIVE_USERS = [
  {
    id: 5,
    displayName: 'alice',
    email: 'alice@example.com',
    avatarUrl: null,
    role: 'MEMBER',
    status: 'ACTIVE',
    createdAt: '2026-10-01T10:00:00Z',
    lastLoginAt: '2026-10-07T09:00:00Z',
  },
];

describe('UsersPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('默认查 ACTIVE，改角色后调角色接口', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, ACTIVE_USERS))
      .mockResolvedValueOnce(jsonResponse(200, { ...ACTIVE_USERS[0], role: 'VIEWER' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<UsersPage />);
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/users?status=ACTIVE');

    const row = screen.getByText('alice').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('combobox'));
    await userEvent.click(await screen.findByTitle('只读'));
    await userEvent.click(within(row).getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/users/5/role'));
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ role: 'VIEWER' });
  });

  it('切换状态筛选会重新查询', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, []));
    vi.stubGlobal('fetch', fetchMock);

    render(<UsersPage />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByRole('combobox', { name: '状态筛选' }));
    await userEvent.click(await screen.findByTitle('已禁用'));

    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('/api/admin/users?status=DISABLED', expect.anything()));
  });

  it('禁用需要二次确认', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, ACTIVE_USERS))
      .mockResolvedValueOnce(jsonResponse(200, { ...ACTIVE_USERS[0], status: 'DISABLED' }));
    vi.stubGlobal('fetch', fetchMock);

    render(<UsersPage />);
    await screen.findByText('alice');
    const row = screen.getByText('alice').closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: /禁\s*用/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));

    await waitFor(() => expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/users/5/disable'));
  });
});
```

- [x] **Step 2: 跑测试，确认失败**

```bash
cd console-web && npm test
```

预期：失败，`Cannot find module '../api/admin'`。

- [x] **Step 3: 写最小实现**

创建 `console-web/src/api/admin.ts`：

```ts
import { request } from './client';
import type { Role, UserStatus } from './auth';

export type UserSummary = {
  id: number;
  displayName: string | null;
  email: string | null;
  avatarUrl: string | null;
  role: Role;
  status: UserStatus;
  createdAt: string;
  lastLoginAt: string | null;
};

export function listUsers(status: UserStatus): Promise<UserSummary[]> {
  return request<UserSummary[]>(`/api/admin/users?status=${status}`);
}

export function approveUser(id: number, role: Role): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/approve`, { method: 'POST', body: { role } });
}

export function rejectUser(id: number): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/reject`, { method: 'POST' });
}

export function changeUserRole(id: number, role: Role): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/role`, { method: 'POST', body: { role } });
}

export function disableUser(id: number): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/disable`, { method: 'POST' });
}
```

把 `console-web/src/pages/ApprovalsPage.tsx` 覆盖为：

```tsx
import { Alert, Button, Card, Select, Space, Table } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { approveUser, listUsers, rejectUser } from '../api/admin';
import type { UserSummary } from '../api/admin';
import type { Role } from '../api/auth';
import { ApiError } from '../api/client';

const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: 'MEMBER', label: '成员' },
  { value: 'STRATEGIST', label: '策略' },
  { value: 'VIEWER', label: '只读' },
  { value: 'ADMIN', label: '管理员' },
];

export function ApprovalsPage() {
  const [rows, setRows] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [roleDraft, setRoleDraft] = useState<Record<number, Role>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listUsers('PENDING'));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '加载待审批列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const runAction = async (id: number, action: () => Promise<UserSummary>) => {
    try {
      await action();
      setRows((current) => current.filter((row) => row.id !== id));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '操作失败，请重试');
    }
  };

  return (
    <Card title="审批队列">
      {error ? <Alert role="alert" type="error" showIcon style={{ marginBottom: 16 }} message={error} /> : null}

      <Table<UserSummary>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '当前没有待审批的申请' }}
        pagination={false}
        columns={[
          { title: '用户', dataIndex: 'displayName', render: (value: string | null) => value ?? '-' },
          { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value ?? '-' },
          {
            title: '申请时间',
            dataIndex: 'createdAt',
            render: (value: string) => new Date(value).toLocaleString('zh-CN'),
          },
          {
            title: '授予角色',
            key: 'role',
            render: (_, row) => (
              <Select<Role>
                aria-label={`授予 ${row.displayName ?? row.id} 的角色`}
                value={roleDraft[row.id] ?? 'MEMBER'}
                options={ROLE_OPTIONS}
                style={{ width: 120 }}
                onChange={(role) => setRoleDraft((current) => ({ ...current, [row.id]: role }))}
              />
            ),
          },
          {
            title: '操作',
            key: 'actions',
            render: (_, row) => (
              <Space>
                <Button
                  type="primary"
                  onClick={() => void runAction(row.id, () => approveUser(row.id, roleDraft[row.id] ?? 'MEMBER'))}
                >
                  通过
                </Button>
                <Button danger onClick={() => void runAction(row.id, () => rejectUser(row.id))}>
                  拒绝
                </Button>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
```

把 `console-web/src/pages/UsersPage.tsx` 覆盖为：

```tsx
import { Alert, Button, Card, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { changeUserRole, disableUser, listUsers } from '../api/admin';
import type { UserSummary } from '../api/admin';
import type { Role, UserStatus } from '../api/auth';
import { ApiError } from '../api/client';

const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: 'MEMBER', label: '成员' },
  { value: 'STRATEGIST', label: '策略' },
  { value: 'VIEWER', label: '只读' },
  { value: 'ADMIN', label: '管理员' },
];

const STATUS_OPTIONS: { value: UserStatus; label: string }[] = [
  { value: 'ACTIVE', label: '已启用' },
  { value: 'PENDING', label: '待审批' },
  { value: 'DISABLED', label: '已禁用' },
  { value: 'REJECTED', label: '已拒绝' },
];

function statusColor(status: UserStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'green';
    case 'PENDING':
      return 'orange';
    case 'DISABLED':
      return 'red';
    default:
      return 'default';
  }
}

export function UsersPage() {
  const [status, setStatus] = useState<UserStatus>('ACTIVE');
  const [rows, setRows] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [roleDraft, setRoleDraft] = useState<Record<number, Role>>({});

  const load = useCallback(async (target: UserStatus) => {
    setLoading(true);
    try {
      setRows(await listUsers(target));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '加载用户列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(status);
  }, [load, status]);

  const applyToRow = async (id: number, action: () => Promise<UserSummary>) => {
    try {
      const updated = await action();
      setRows((current) => current.map((row) => (row.id === id ? updated : row)));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '操作失败，请重试');
    }
  };

  return (
    <Card
      title="用户管理"
      extra={
        <Select<UserStatus>
          aria-label="状态筛选"
          value={status}
          options={STATUS_OPTIONS}
          style={{ width: 140 }}
          onChange={setStatus}
        />
      }
    >
      {error ? <Alert role="alert" type="error" showIcon style={{ marginBottom: 16 }} message={error} /> : null}

      <Table<UserSummary>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '用户', dataIndex: 'displayName', render: (value: string | null) => value ?? '-' },
          { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value ?? '-' },
          {
            title: '状态',
            dataIndex: 'status',
            render: (value: UserStatus) => <Tag color={statusColor(value)}>{value}</Tag>,
          },
          {
            title: '角色',
            key: 'role',
            render: (_, row) => (
              <Select<Role>
                aria-label={`${row.displayName ?? row.id} 的角色`}
                value={roleDraft[row.id] ?? row.role}
                options={ROLE_OPTIONS}
                style={{ width: 120 }}
                onChange={(role) => setRoleDraft((current) => ({ ...current, [row.id]: role }))}
              />
            ),
          },
          {
            title: '操作',
            key: 'actions',
            render: (_, row) => (
              <Space>
                <Button
                  disabled={(roleDraft[row.id] ?? row.role) === row.role}
                  onClick={() => void applyToRow(row.id, () => changeUserRole(row.id, roleDraft[row.id] ?? row.role))}
                >
                  保存
                </Button>
                <Popconfirm
                  title="禁用该账号？"
                  description="该用户会被立即踢下线，刷新令牌一并作废。"
                  okText="确定"
                  cancelText="取消"
                  onConfirm={() => void applyToRow(row.id, () => disableUser(row.id))}
                >
                  <Button danger disabled={row.status === 'DISABLED'}>
                    禁用
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
```

- [x] **Step 4: 跑测试，确认通过**

```bash
cd console-web && npm test
```

预期：`Tests 42 passed`。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web && git commit -m "feat(console-web): 审批队列与用户管理"
```

---

### Task 10: 生产构建与 Nginx 反代

**Files:**
- Create: `deploy/nginx/default.conf`
- Modify: `deploy/docker-compose.yml`（nginx 服务挂配置与静态产物）
- Modify: `README.md`（前端运行与生产构建）
- Test: 无新增测试；本任务的验证是构建产物与配置检查

**设计要点：** 生产期静态产物与 `/api` 必须同源，否则 refresh Cookie 的 `SameSite=Lax`
和刷新接口的 `Origin` 校验都会失效。`auth-service` 目前跑在宿主机上（不在 compose 里），
所以 Nginx 反代目标是 `host.docker.internal:8080`；将来把它容器化后改成服务名即可。

- [x] **Step 1: 先跑一次完整校验，确认当前状态**

```bash
cd console-web && npm run typecheck && npm test
```

预期：类型检查无输出、`Tests 42 passed`。

- [x] **Step 2: 构建**

```bash
cd console-web && npm run build
```

实测：**一次通过**。原先担心 `tsc` 会把 `src/test/setup.ts` 与测试文件当编译输入报错，
实际 `@types/react` 提供了全局 `React` 命名空间，`include: ["src", ...]` 只是让测试一起过类型，
并不出错，所以不需要排除 `src/test`。唯一输出是 Vite 的告警：
antd 让主包约 980 kB（gzip 311 kB）超过 500 kB 提示线。内部工具可接受，
真要优化再上 `manualChunks` 拆 antd，本任务不做。

- [x] **Step 3: 写实现**

创建 `deploy/nginx/default.conf`：

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # 静态产物与 /api 同源：浏览器只看到一个 origin，
    # refresh token 的 Cookie 才能用 SameSite=Lax，刷新接口的 Origin 校验也才过得去。
    location /api/ {
        proxy_pass http://host.docker.internal:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SPA 路由：直接刷新 /admin/approvals 也要能命中 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    location = /index.html {
        add_header Cache-Control "no-store";
    }
}
```

把 `deploy/docker-compose.yml` 里的 nginx 服务改成：

```yaml
  # 生产/联调形态：Nginx 同时托管前端静态产物并反代 /api。
  # 首次使用前执行一次 docker pull nginx:1.27-alpine。
  # 注意：这种形态下浏览器 origin 是 http://localhost，
  # 必须同步把 auth-service 的 CONSOLE_BASE_URL 设成 http://localhost，
  # 否则刷新接口会因 Origin 不一致返回 403 cross_origin。
  nginx:
    image: nginx:1.27-alpine
    container_name: data-design-nginx
    profiles: ["edge"]
    ports:
      - "${NGINX_PORT:-80}:80"
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
      - ../console-web/dist:/usr/share/nginx/html:ro
```

在 `README.md` 的「本地运行」里追加前端两节：

```markdown
### 5. 起前端控制台（开发期）

```bash
cd console-web && npm install && npm run dev
```

开发服务器在 5173，`/api` 由 Vite 代理到 8080，浏览器侧始终是同一个 origin。

### 6. 生产构建与 Nginx 形态

```bash
cd console-web && npm run build
docker pull nginx:1.27-alpine
cd .. && CONSOLE_BASE_URL=http://localhost docker compose -f deploy/docker-compose.yml --profile edge up -d nginx
```

Nginx 在 80 端口同时托管静态产物与反代 `/api`。**此时必须把 `CONSOLE_BASE_URL`
设成浏览器实际访问的地址**（即 `http://localhost`），否则刷新接口的 `Origin` 校验会拒绝请求。
```

- [x] **Step 4: 验证构建产物**

```bash
cd console-web && npm run build && ls -la dist/index.html dist/assets | head
```

预期：`dist/index.html` 存在，`dist/assets/` 下有带哈希的 js 与 css。
把 `dist/index.html` 里引用的资源名与 `dist/assets/` 实际文件名对一遍，确认没有 404 风险。

- [x] **Step 5: 提交**

```bash
cd .. && git add console-web deploy README.md && git commit -m "feat(deploy): 前端生产构建与 Nginx 反代配置"
```

---

## 计划自检（writing-plans 复核）

### Spec 覆盖对照

| Spec 章节 | 覆盖它的 Task |
|---|---|
| §6.1 路由表（含 `/login/local` 不出现在导航） | Task 5（路由与守卫）、Task 6（登录页）、Task 7（状态页） |
| §6.2 控制台骨架（侧栏分组菜单、顶栏面包屑、环境标识、用户菜单、不闪白屏） | Task 8（骨架）、Task 5（守卫占位文案） |
| §6.3 视觉规范（渐变背景、网格线、卡片、主色、控件圆角、侧栏配色） | Task 2（token 与主题）、Task 6/7（页面套用 `ds-page`/`ds-card`） |
| §5.3 管理员操作（审批 + 赋角色一次完成） | Task 9（审批队列行内选角色） |
| §5.6 前端刷新（access token 放内存、启动静默续期） | Task 3（client 静默续期）、Task 4（启动引导） |
| §5.7 安全基线（同源、不落 localStorage） | Task 3（令牌只在内存）、Task 10（Nginx 同源反代） |
| §3.2 运行时拓扑（开发期 Vite 代理、生产期 Nginx 同源） | Task 1（Vite proxy）、Task 10（Nginx） |
| §7 遗留子项目（用户列表筛选、批量操作、审计查看） | 明确不在本计划范围 |

### 类型与命名一致性

- 后端字段名（`displayName`、`avatarUrl`、`accessToken`、`expiresInSeconds`、`appliedAt`、
  `breakGlass`）与 `api/auth.ts`、`api/admin.ts` 的类型逐一对齐
- 角色枚举 `ADMIN｜MEMBER｜STRATEGIST｜VIEWER` 与状态枚举 `PENDING｜ACTIVE｜DISABLED｜REJECTED`
  取自后端 enum，前端只做映射不改写
- 错误码（`unauthenticated`、`invalid_credentials`、`too_many_attempts`、`account_not_active`、
  `cannot_modify_self`、`user_not_found`、`cross_origin`）都来自后端 `ApiException`，
  前端只消费不臆造

### 执行过程中新发现并修正的问题

| # | 位置 | 现象 | 处理 |
|---|---|---|---|
| 1 | Task 3 · `api/client.test.ts`「把错误响应翻译成带 code 的 ApiError」 | 同一处用 `mockResolvedValue` 复用同一个 `Response` 实例，`request()` 连调两次：第一次已把 body 读掉，第二次抛 `TypeError: Body is unusable`，测试拿到的不是 `ApiError` | 改为 `mockImplementation(() => Promise.resolve(jsonResponse(...)))`，每次调用都产出新的 `Response`。这是测试写法问题，实现无需改动 |
| 2 | Task 5 · 占位 `LoginPage.tsx` | 计划给占位页只留了 `<h1>登录</h1>`，但 `App.test.tsx` 同时断言产品名，Task 5 阶段 `App.test.tsx` 必挂 | 占位页补上 `<p>信贷风控决策引擎</p>`；Task 6 的真实登录页本来就有 `ds-card__brand` 品牌名，替换后断言继续成立 |
| 3 | Task 5 · `AppRoutes.test.tsx` | 计划预期 Task 5 新增 6 个路由测试（累计 20），但代码块里只有 5 个：`RequireAuth` 的 `disabled` 分支没有任何测试覆盖 | 补一个「被停用用户访问控制台会被送到登录页」，覆盖 `status === 'disabled'` 分支；累计数回到 20，Task 6–9 的预期数（26/30/35/42）继续成立 |
| 4 | Task 5 · `AuthContext.tsx` 的 `SessionStatus` | 计划写成 `'loading' \| 'anonymous' \| UserStatus \| 'active'`，但 `AuthContext` 落状态时调了 `session.status.toLowerCase()`，`RequireAuth` 也只比较小写；类型与运行时值不一致，`tsc` 报 TS2367「"rejected" 与 UserStatus 无重叠」，守卫分支在类型层面永远不成立 | 改成字面量小写联合 `'loading' \| 'anonymous' \| 'active' \| 'pending' \| 'rejected' \| 'disabled'`，并去掉不再使用的 `UserStatus` 导入；Task 5 Step 4 补跑 `npm run typecheck` |
| 5 | Task 6 · `LoginPage.test.tsx` | 测试只包了 `MemoryRouter`，而真实 `LoginPage` 内部调 `useAuth()`，渲染直接抛「useAuth 必须在 AuthProvider 内使用」 | 测试的 `renderLogin` 外面补一层 `AuthProvider`（`LocalLoginPage.test.tsx` 计划里本来就包了，是登录页漏了） |
| 6 | Task 6 · `LocalLoginPage.test.tsx` | antd 对「正好两个汉字」的按钮会自动插空格，按钮的可访问名是 `登 录`，`getByRole('button', { name: '登录' })` 找不到 | 断言改成 `/登\s*录/`；不动 antd 的展示行为，页面测试也不经过 `ConfigProvider`，改测试更稳 |
| 7 | Task 6 · `App.test.tsx` / `AppRoutes.test.tsx` | 真实登录页用 `ds-card__brand` 承载品牌名，没有 `h1 登录`，Task 5 的 3 处 `getByRole('heading', { name: '登录' })` 全部失效 | 三处改成断言登录页主操作 `getByRole('button', { name: /使用 GitHub 登录/ })`，并把这两个测试文件补进 Task 6 的 Files 列表 |
| 8 | Task 7 · `StatusPages.test.tsx`「回调中转页换到令牌后进入控制台」 | 该用例挂载了 `AuthProvider`，bootstrap 自己也会打 `/api/auth/session` 与 `/api/auth/refresh`，而子组件 effect 先于父组件执行，实际调用序是 `refresh` → `session` → `/api/me`；计划里 3 个 `mockResolvedValueOnce` 全部错位——bootstrap 拿到 `{accessToken}` 当会话、在 `status.toLowerCase()` 上抛 TypeError 被自己的 catch 吞掉并置为 anonymous，断言只是被 `waitFor` 抢在状态翻转之前满足了，属于**假绿** | 改用按 URL 分发的 `mockImplementation`：回调页与 bootstrap 各自拿到正确响应，断言不再依赖调用顺序 |
| 9 | Task 8 · `AppRoutes.test.tsx`「非管理员访问管理页会被送回 Dashboard」 | 断言 `getByRole('heading', { name: 'Dashboard' })`，但 Task 8 把 `DashboardPage` 换成了 `<Card title="概览">`——卡片标题不是 heading，且顶栏面包屑也有「概览」，`getByText('概览')` 会撞车 | 改成断言该卡片独有的正文文案 `getByText(/决策流、规则与监控尚未接入/)` |
| 10 | Task 8 · `AppLayout.test.tsx`「未实现的菜单项不可点」 | 断言 `getByText('规则中心').closest('li')` 带 `aria-disabled`，但「规则中心」是 `type: 'group'` 的分组标签，它那个 `<li>` 没有该属性，被禁用的是组里的菜单项 | 指向真正的菜单项 `getByText('规则与策略')`，再断言最近的 `<li>` |
| 11 | Task 8 · `AppLayout.test.tsx`「面包屑跟随路由」 | `getByText('审批队列')` 同时命中侧栏菜单项与页面标题，报「Found multiple elements」 | 先用 `findByRole('navigation', { name: '面包屑' })` 拿到面包屑，再用 `within(breadcrumb)` 在内部断言，导入补上 `within` |
| 12 | Task 1 · `tsconfig.json` | `EnvBadge` 用 `import.meta.env.VITE_APP_ENV`，但 `types` 只列了 `vitest/globals` 与 `@testing-library/jest-dom`，`tsc` 报 TS2339「Property 'env' does not exist on type 'ImportMeta'」 | `types` 补 `vite/client`；Task 1 的 tsconfig 片段同步更新 |
| 13 | Task 9 · `ApprovalsPage.tsx` | 从 antd 导入了 `Tag` 但表格列里根本没用到，`tsc` 报 TS6133 | 去掉 `Tag` 导入（`UsersPage.tsx` 里的 `Tag` 用到了，保留） |
| 14 | Task 9 · `ApprovalsPage.test.tsx` / `UsersPage.test.tsx` | 同 Task 6 的问题：「通过/拒绝/保存/禁用/确定」都是两个汉字，antd 会插空格（`通 过`），按精确名字取按钮全部失败 | 这些按钮统一改成 `/通\s*过/`、`/拒\s*绝/`、`/保\s*存/`、`/禁\s*用/`、`/确\s*定/` |
| 15 | Task 5 · `AppRoutes.test.tsx`「管理员能进入审批队列」 | 断言 `heading 审批队列`，但 Task 9 把该页换成 `<Card title="审批队列">`（卡片标题不是 heading），且侧栏有同名菜单项，`getByText` 也会撞车 | 改断言该页加载完成后的空态文案「当前没有待审批的申请」，顺带验证它真的发了请求 |
| 16 | Task 10 · Step 2 的预期 | 计划写「`npm run build` 预期失败」，担心 `tsc` 把 `src/test` 当编译输入、或 `React.ReactElement` 找不到命名空间 | 实测一次通过：`@types/react` 提供全局 `React` 命名空间，测试文件一起过类型也没问题，无需在 `include` 里排除 `src/test`；Step 2 文案已按实测改写，并记录 antd 主包 ~980 kB（gzip 311 kB）的 Vite 体积告警 |
### 启动验证（Task 10 实测）

`NGINX_PORT=18080 docker compose -f deploy/docker-compose.yml --profile edge up -d nginx` 后：

| 请求 | 结果 |
|---|---|
| `GET /` | 200，返回 `dist/index.html` |
| `GET /admin/approvals`（SPA 深链） | 200，`try_files` 回落命中 index.html |
| `GET /assets/index-*.js` | 200，981785 字节 |
| `GET /api/me`（后端未起） | 502，nginx 日志为 `connect() failed (111: Connection refused)`，上游解析到 `host.docker.internal` → 192.168.65.254:8080 |

`dist/index.html` 引用 `/assets/index-DDYVLzCl.js` 与 `/assets/index-tlhGNd0v.css`，与 `dist/assets/` 下的实际文件名一致，无 404 风险。
### 端到端联调验证（计划 B 收尾实测）

本计划的测试全部用手写的 `fetch` mock，只能证明「前端按约定的契约工作」。收尾时又用**真实后端**跑了一遍关键路径，验证契约本身没有偏差：

```bash
cd console-web && npm run dev                        # 5173
cd auth-service && SPRING_DATASOURCE_URL='jdbc:mysql://localhost:13306/data_design?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
  SPRING_DATA_REDIS_PORT=16379 CONSOLE_BASE_URL='http://localhost:5173' \
  BREAK_GLASS_ADMIN_USERNAME=admin BREAK_GLASS_ADMIN_PASSWORD_HASH='<bcrypt>' \
  ./scripts/mvn spring-boot:run                      # 8080
```

| 验证项 | 结果 |
|---|---|
| 匿名访问 `/` | 后端 401 → 落到 `/login`，卡片、渐变背景、网格线、GitHub 按钮与设计稿一致 |
| `/login/local` 破窗登录 | `admin` + 密码登录成功，跳转 `/` 并进入控制台骨架 |
| 控制台骨架 | 侧栏四个分组、未实现项 `disabled`、面包屑「首页 / 概览」、橙色「开发环境」标识、顶栏 `admin` + `ADMIN` 标签 |
| 审批队列 | 直接插 `PENDING` 用户后列表正常渲染；行内选「策略」再点通过 → `users.role=STRATEGIST`、`status=ACTIVE`、`approved_by=1`，审计落 `APPROVED {"role":"STRATEGIST","adminId":1}` |
| 拒绝 | `viewer-wang` → `status=REJECTED`，审计落 `REJECTED` |
| 用户管理 | 改角色后「保存」由禁用变可用、保存成功后重新禁用；改「只读」落库为 `VIEWER` |
| 自禁用保护 | 禁用自己 → 后端 400 `cannot_modify_self` → 页面就地 `Alert` 显示「不能禁用自己的账号」 |
| 禁用 | `octocat` → 状态标签变为 `DISABLED` |

结论：`api/auth.ts`、`api/admin.ts` 里的字段名与状态/角色枚举和后端完全对齐，无需返工。
