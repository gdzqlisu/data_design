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
