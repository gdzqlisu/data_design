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
