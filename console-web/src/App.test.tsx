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

    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument());
    expect(screen.getByText('信贷风控决策引擎')).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
