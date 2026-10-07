import { CloseCircleOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

export function RejectedPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="ds-page">
      <div className="ds-card ds-card--wide" style={{ textAlign: 'center' }}>
        <CloseCircleOutlined style={{ fontSize: 32, color: '#cf1322' }} />
        <h1 style={{ marginTop: 16, fontSize: 20 }}>申请未通过</h1>
        <p style={{ color: '#64748b', fontSize: 13, lineHeight: 1.9 }}>
          本次访问申请未通过。如果认为这是误判，请联系管理员核实身份后重新提交。
        </p>
        <Button onClick={() => void onLogout()}>退出登录</Button>
      </div>
    </div>
  );
}
