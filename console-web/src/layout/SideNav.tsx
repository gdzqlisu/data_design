import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FundOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Menu } from 'antd';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';

type MenuItem = Required<MenuProps>['items'][number];

// 本次只通「系统管理 → 审批 / 用户」，其余是占位，一律 disabled：
// 点了没反应好过点了进空页面。
const ITEMS: MenuItem[] = [
  {
    key: 'grp-decision',
    label: '决策中心',
    type: 'group',
    children: [
      { key: 'decision-flow', label: '决策流编排', icon: <ApartmentOutlined />, disabled: true },
      { key: 'decision-run', label: '决策执行', icon: <DeploymentUnitOutlined />, disabled: true },
    ],
  },
  {
    key: 'grp-rule',
    label: '规则中心',
    type: 'group',
    children: [
      { key: 'rule-list', label: '规则与策略', icon: <AuditOutlined />, disabled: true },
      { key: 'variable', label: '变量与数据接入', icon: <FundOutlined />, disabled: true },
    ],
  },
  {
    key: 'grp-monitor',
    label: '监控中心',
    type: 'group',
    children: [{ key: 'monitor-dashboard', label: '执行监控', icon: <DashboardOutlined />, disabled: true }],
  },
  {
    key: 'grp-system',
    label: '系统管理',
    type: 'group',
    children: [
      { key: '/admin/approvals', label: '审批队列', icon: <AuditOutlined /> },
      { key: '/admin/users', label: '用户管理', icon: <TeamOutlined /> },
    ],
  },
];

export function SideNav() {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (
    <Menu
      theme="dark"
      mode="inline"
      selectedKeys={[pathname]}
      items={ITEMS}
      onClick={({ key }) => navigate(key)}
      style={{ borderInlineEnd: 'none' }}
    />
  );
}
