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
