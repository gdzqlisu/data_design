import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Avatar, Breadcrumb, Button, Dropdown, Layout, Space, Tag } from 'antd';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';
import { EnvBadge } from './EnvBadge';

const CRUMBS: Record<string, string[]> = {
  '/': ['概览'],
  '/admin/approvals': ['系统管理', '审批队列'],
  '/admin/users': ['系统管理', '用户管理'],
};

export function TopBar() {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const trail = CRUMBS[pathname] ?? ['概览'];

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <Layout.Header
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingInline: 24,
        borderBottom: '1px solid #eef1f6',
      }}
    >
      <Breadcrumb
        aria-label="面包屑"
        items={[
          { title: <Link to="/">首页</Link> },
          ...trail.map((label) => ({ title: label })),
        ]}
      />

      <Space size={16}>
        <EnvBadge />
        <Dropdown
          menu={{
            items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
            onClick: ({ key }) => {
              if (key === 'logout') {
                void onLogout();
              }
            },
          }}
        >
          <Button type="text" style={{ height: 40, paddingInline: 8 }}>
            <Space size={8}>
              <Avatar size={28} src={user?.avatarUrl || undefined} icon={<UserOutlined />} />
              <span>{user?.displayName ?? '未命名'}</span>
              <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                {user?.role ?? '-'}
              </Tag>
            </Space>
          </Button>
        </Dropdown>
      </Space>
    </Layout.Header>
  );
}
