import { ClockCircleOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function PendingPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="ds-page">
      <div className="ds-card ds-card--wide" style={{ textAlign: 'center' }}>
        <ClockCircleOutlined style={{ fontSize: 32, color: '#1d4ed8' }} />
        <h1 style={{ marginTop: 16, fontSize: 20 }}>等待审批</h1>
        <p style={{ color: '#64748b', fontSize: 13, lineHeight: 1.9 }}>
          {user?.displayName ? `${user.displayName}，` : ''}
          你的账号已提交，管理员审批通过后即可进入控制台。
          <br />
          审批结果在下次登录时生效。
        </p>
        <Button onClick={() => void onLogout()}>退出登录</Button>
      </div>
    </div>
  );
}
