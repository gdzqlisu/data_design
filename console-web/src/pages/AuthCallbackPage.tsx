import { Spin } from 'antd';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function AuthCallbackPage() {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;

    void (async () => {
      try {
        await completeLogin();
        navigate('/', { replace: true });
      } catch {
        navigate('/login?error=oauth_failed', { replace: true });
      }
    })();
  }, [completeLogin, navigate]);

  return (
    <div className="ds-page">
      <div className="ds-card" style={{ textAlign: 'center' }}>
        <Spin size="large" />
        <p style={{ marginTop: 20, marginBottom: 0, color: '#64748b', fontSize: 13 }}>正在登录…</p>
      </div>
    </div>
  );
}
