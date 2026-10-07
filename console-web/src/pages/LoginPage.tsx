import { InfoCircleOutlined } from '@ant-design/icons';
import { Alert, Button } from 'antd';
import { useSearchParams } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';

const ERROR_MESSAGES: Record<string, string> = {
  oauth_failed: 'GitHub 授权失败，请重试。',
  state_expired: '登录会话已过期，请重新发起登录。',
  disabled: '账号已被停用，请联系管理员。',
  provider_not_configured: '服务端尚未配置 GitHub 登录凭据，请联系管理员。',
};

export function LoginPage() {
  const { loginWithGitHub } = useAuth();
  const [params] = useSearchParams();
  const error = params.get('error');

  return (
    <div className="ds-page">
      <div className="ds-card">
        <p className="ds-card__brand">信贷风控决策引擎</p>
        <p className="ds-card__subtitle">内部研发与策略人员专用</p>

        {error ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 20 }}
            message={ERROR_MESSAGES[error] ?? '登录失败，请重试。'}
          />
        ) : null}

        <Button type="primary" size="large" block onClick={loginWithGitHub}>
          使用 GitHub 登录
        </Button>

        <p style={{ marginTop: 20, marginBottom: 0, fontSize: 12, color: '#94a3b8' }}>
          <InfoCircleOutlined style={{ marginRight: 6 }} />
          首次登录需要管理员审批，通过后即可进入控制台。
        </p>
      </div>
    </div>
  );
}
