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
