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
