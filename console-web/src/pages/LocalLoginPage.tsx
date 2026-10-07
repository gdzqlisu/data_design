import { Alert, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

type FormValues = { username: string; password: string };

export function LocalLoginPage() {
  const { loginWithLocal } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: FormValues) => {
    setSubmitting(true);
    setError(null);
    try {
      await loginWithLocal(values.username, values.password);
      navigate('/', { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError && caught.code === 'too_many_attempts') {
        setError('尝试次数过多，请稍后再试。');
      } else if (caught instanceof ApiError) {
        setError(caught.message);
      } else {
        setError('登录失败，请重试。');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="ds-page">
      <div className="ds-card">
        <p className="ds-card__brand">破窗登录</p>
        <p className="ds-card__subtitle">仅供 GitHub 不可用时使用，账号由环境变量下发</p>

        {error ? <Alert type="error" showIcon style={{ marginBottom: 20 }} message={error} /> : null}

        <Form<FormValues> layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input autoComplete="username" size="large" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" size="large" />
          </Form.Item>
          <Button type="primary" size="large" block htmlType="submit" loading={submitting}>
            登录
          </Button>
        </Form>
      </div>
    </div>
  );
}
