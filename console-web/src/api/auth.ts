import { request } from './client';

export type Role = 'ADMIN' | 'MEMBER' | 'STRATEGIST' | 'VIEWER';
export type UserStatus = 'PENDING' | 'ACTIVE' | 'DISABLED' | 'REJECTED';

export type SessionResponse = {
  id: number;
  displayName: string | null;
  avatarUrl: string | null;
  role: Role;
  status: UserStatus;
  appliedAt: string | null;
};

export type CurrentUser = {
  id: number;
  displayName: string;
  email: string;
  avatarUrl: string;
  role: Role;
  status: UserStatus;
  breakGlass: boolean;
};

export type TokenResponse = {
  accessToken: string;
  expiresInSeconds: number;
  role: Role;
  status: UserStatus;
};

export function fetchSession(): Promise<SessionResponse> {
  return request<SessionResponse>('/api/auth/session', { skipRefresh: true });
}

export function fetchCurrentUser(): Promise<CurrentUser> {
  return request<CurrentUser>('/api/me');
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST', skipRefresh: true });
}

export function loginWithLocal(username: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>('/api/auth/local/login', {
    method: 'POST',
    body: { username, password },
    skipRefresh: true,
  });
}

/** GitHub 登录是整页跳转，不是 fetch：要离开 SPA 去 GitHub。 */
export function goToGitHubAuthorize(): void {
  window.location.assign('/api/auth/github/authorize');
}
