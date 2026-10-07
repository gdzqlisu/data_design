import { Alert, Button, Card, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { changeUserRole, disableUser, listUsers } from '../api/admin';
import type { UserSummary } from '../api/admin';
import type { Role, UserStatus } from '../api/auth';
import { ApiError } from '../api/client';

const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: 'MEMBER', label: '成员' },
  { value: 'STRATEGIST', label: '策略' },
  { value: 'VIEWER', label: '只读' },
  { value: 'ADMIN', label: '管理员' },
];

const STATUS_OPTIONS: { value: UserStatus; label: string }[] = [
  { value: 'ACTIVE', label: '已启用' },
  { value: 'PENDING', label: '待审批' },
  { value: 'DISABLED', label: '已禁用' },
  { value: 'REJECTED', label: '已拒绝' },
];

function statusColor(status: UserStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'green';
    case 'PENDING':
      return 'orange';
    case 'DISABLED':
      return 'red';
    default:
      return 'default';
  }
}

export function UsersPage() {
  const [status, setStatus] = useState<UserStatus>('ACTIVE');
  const [rows, setRows] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [roleDraft, setRoleDraft] = useState<Record<number, Role>>({});

  const load = useCallback(async (target: UserStatus) => {
    setLoading(true);
    try {
      setRows(await listUsers(target));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '加载用户列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(status);
  }, [load, status]);

  const applyToRow = async (id: number, action: () => Promise<UserSummary>) => {
    try {
      const updated = await action();
      setRows((current) => current.map((row) => (row.id === id ? updated : row)));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '操作失败，请重试');
    }
  };

  return (
    <Card
      title="用户管理"
      extra={
        <Select<UserStatus>
          aria-label="状态筛选"
          value={status}
          options={STATUS_OPTIONS}
          style={{ width: 140 }}
          onChange={setStatus}
        />
      }
    >
      {error ? <Alert role="alert" type="error" showIcon style={{ marginBottom: 16 }} message={error} /> : null}

      <Table<UserSummary>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: '用户', dataIndex: 'displayName', render: (value: string | null) => value ?? '-' },
          { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value ?? '-' },
          {
            title: '状态',
            dataIndex: 'status',
            render: (value: UserStatus) => <Tag color={statusColor(value)}>{value}</Tag>,
          },
          {
            title: '角色',
            key: 'role',
            render: (_, row) => (
              <Select<Role>
                aria-label={`${row.displayName ?? row.id} 的角色`}
                value={roleDraft[row.id] ?? row.role}
                options={ROLE_OPTIONS}
                style={{ width: 120 }}
                onChange={(role) => setRoleDraft((current) => ({ ...current, [row.id]: role }))}
              />
            ),
          },
          {
            title: '操作',
            key: 'actions',
            render: (_, row) => (
              <Space>
                <Button
                  disabled={(roleDraft[row.id] ?? row.role) === row.role}
                  onClick={() => void applyToRow(row.id, () => changeUserRole(row.id, roleDraft[row.id] ?? row.role))}
                >
                  保存
                </Button>
                <Popconfirm
                  title="禁用该账号？"
                  description="该用户会被立即踢下线，刷新令牌一并作废。"
                  okText="确定"
                  cancelText="取消"
                  onConfirm={() => void applyToRow(row.id, () => disableUser(row.id))}
                >
                  <Button danger disabled={row.status === 'DISABLED'}>
                    禁用
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
