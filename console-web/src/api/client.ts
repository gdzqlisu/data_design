export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// access token 只放在内存里：刷新页面即丢，靠 httpOnly Cookie 静默续期。
// 不落 localStorage，XSS 就偷不到持久凭证。
let accessToken: string | null = null;
let sessionLostHandler: (() => void) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setSessionLostHandler(handler: (() => void) | null): void {
  sessionLostHandler = handler;
}

export type RequestOptions = {
  method?: 'GET' | 'POST';
  body?: unknown;
  /** 认证相关请求自己处理 401，不能让 client 再递归续期 */
  skipRefresh?: boolean;
};

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {};
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  return fetch(path, {
    method: options.method ?? 'GET',
    headers,
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const payload: unknown = text ? JSON.parse(text) : null;
  if (!response.ok) {
    const body = (payload ?? {}) as { code?: string; message?: string };
    throw new ApiError(
      response.status,
      body.code ?? 'unknown_error',
      body.message ?? `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as T;
}

/**
 * 用 httpOnly Cookie 换新的 access token。
 * 故意不走 request()：否则 401 时会自己调自己。
 */
export async function renewSession(): Promise<boolean> {
  const response = await fetch('/api/auth/refresh', {
    method: 'POST',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    accessToken = null;
    return false;
  }
  const body = (await response.json()) as { accessToken: string };
  accessToken = body.accessToken;
  return true;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options);

  if (response.status === 401 && !options.skipRefresh) {
    if (await renewSession()) {
      response = await send(path, options);
    } else {
      sessionLostHandler?.();
    }
  }

  return parse<T>(response);
}
