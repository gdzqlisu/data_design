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
