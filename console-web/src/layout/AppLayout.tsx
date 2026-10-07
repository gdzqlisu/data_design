import { Layout } from 'antd';
import { Outlet } from 'react-router-dom';

import { SideNav } from './SideNav';
import { TopBar } from './TopBar';
import { tokens } from '../theme/tokens';

export function AppLayout() {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider width={216} style={{ background: tokens.siderBg }}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            paddingInline: 20,
            color: '#ffffff',
            fontWeight: 600,
            letterSpacing: 0.5,
          }}
        >
          风控决策引擎
        </div>
        <SideNav />
      </Layout.Sider>
      <Layout>
        <TopBar />
        <Layout.Content style={{ padding: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
