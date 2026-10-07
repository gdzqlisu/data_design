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

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument());
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

    await waitFor(() => expect(screen.getByRole('heading', { name: '审批队列' })).toBeInTheDocument());
  });
});
