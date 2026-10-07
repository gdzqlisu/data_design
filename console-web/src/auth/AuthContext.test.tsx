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
