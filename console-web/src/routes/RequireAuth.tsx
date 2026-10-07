import { Navigate } from 'react-router-dom';
import type { ReactElement } from 'react';

import { useAuth } from '../auth/AuthContext';
import type { SessionStatus } from '../auth/AuthContext';

type Props = {
  /** 允许进入的会话状态，默认只允许 ACTIVE */
  allow?: SessionStatus[];
  /** 额外的角色要求 */
  role?: string;
  children: ReactElement;
};

export function RequireAuth({ allow = ['active'], role, children }: Props) {
  const { status, user } = useAuth();

  if (status === 'loading') {
    return <div style={{ padding: 48, textAlign: 'center', color: '#64748b' }}>正在校验会话…</div>;
  }
  if (status === 'anonymous' || status === 'disabled') {
    return <Navigate to="/login" replace />;
  }
  if (status === 'pending') {
    return allow.includes('pending') ? children : <Navigate to="/pending" replace />;
  }
  if (status === 'rejected') {
    return allow.includes('rejected') ? children : <Navigate to="/rejected" replace />;
  }
  if (!allow.includes('active')) {
    return <Navigate to="/" replace />;
  }
  if (role && user?.role !== role) {
    return <Navigate to="/" replace />;
  }
  return children;
}
