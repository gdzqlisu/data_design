import { Card } from 'antd';

export function DashboardPage() {
  return (
    <Card title="概览">
      <p style={{ margin: 0, color: '#64748b' }}>
        决策流、规则与监控尚未接入。当前可用的功能在「系统管理」下：审批队列与用户管理。
      </p>
    </Card>
  );
}
