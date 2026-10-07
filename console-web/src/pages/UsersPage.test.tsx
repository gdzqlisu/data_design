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
