import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';

import {
  fetchCurrentUser,
  fetchSession,
  goToGitHubAuthorize,
  loginWithLocal as loginWithLocalRequest,
  logout as logoutRequest,
} from '../api/auth';
import type { CurrentUser, Role } from '../api/auth';
import { ApiError, renewSession, setAccessToken, setSessionLostHandler } from '../api/client';

// 会话状态对外一律小写：AuthContext 用 session.status.toLowerCase() 落状态，
// RequireAuth 也只比较小写，写成 UserStatus 原样（大写）会让守卫分支永远不成立。
export type SessionStatus = 'loading' | 'anonymous' | 'active' | 'pending' | 'rejected' | 'disabled';

type AuthContextValue = {
  status: SessionStatus;
  user: CurrentUser | null;
  loginWithGitHub: () => void;
  completeLogin: () => Promise<void>;
  loginWithLocal: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [user, setUser] = useState<CurrentUser | null>(null);
  const bootstrapped = useRef(false);

  const forgetSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    setStatus('anonymous');
  }, []);

  useEffect(() => {
    setSessionLostHandler(forgetSession);
    return () => setSessionLostHandler(null);
  }, [forgetSession]);

  useEffect(() => {
    if (bootstrapped.current) {
      return;
    }
    bootstrapped.current = true;

    void (async () => {
      try {
        const session = await fetchSession();
        if (session.status !== 'ACTIVE') {
          // PENDING / REJECTED / DISABLED 不需要 access token：
          // 它们的页面只用会话信息就能渲染，别去消耗刷新链。
          setStatus(session.status.toLowerCase() as SessionStatus);
          return;
        }
        if (!(await renewSession())) {
          forgetSession();
          return;
        }
        setUser(await fetchCurrentUser());
        setStatus('active');
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          forgetSession();
          return;
        }
        forgetSession();
      }
    })();
  }, [forgetSession]);

  const completeLogin = useCallback(async () => {
    if (!(await renewSession())) {
      forgetSession();
      throw new ApiError(401, 'unauthenticated', '登录未完成，请重新登录');
    }
    setUser(await fetchCurrentUser());
    setStatus('active');
  }, [forgetSession]);

  const loginWithLocal = useCallback(
    async (username: string, password: string) => {
      const token = await loginWithLocalRequest(username, password);
      setAccessToken(token.accessToken);
      setUser(await fetchCurrentUser());
      setStatus('active');
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      forgetSession();
    }
  }, [forgetSession]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, loginWithGitHub: goToGitHubAuthorize, completeLogin, loginWithLocal, logout }),
    [status, user, completeLogin, loginWithLocal, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return value;
}

export type { Role };
