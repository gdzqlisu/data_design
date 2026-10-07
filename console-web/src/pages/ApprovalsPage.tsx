import { Alert, Button, Card, Select, Space, Table } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { approveUser, listUsers, rejectUser } from '../api/admin';
import type { UserSummary } from '../api/admin';
import type { Role } from '../api/auth';
import { ApiError } from '../api/client';

const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: 'MEMBER', label: '成员' },
  { value: 'STRATEGIST', label: '策略' },
  { value: 'VIEWER', label: '只读' },
  { value: 'ADMIN', label: '管理员' },
];

export function ApprovalsPage() {
  const [rows, setRows] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [roleDraft, setRoleDraft] = useState<Record<number, Role>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listUsers('PENDING'));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '加载待审批列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const runAction = async (id: number, action: () => Promise<UserSummary>) => {
    try {
      await action();
      setRows((current) => current.filter((row) => row.id !== id));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : '操作失败，请重试');
    }
  };

  return (
    <Card title="审批队列">
      {error ? <Alert role="alert" type="error" showIcon style={{ marginBottom: 16 }} message={error} /> : null}

      <Table<UserSummary>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '当前没有待审批的申请' }}
        pagination={false}
        columns={[
          { title: '用户', dataIndex: 'displayName', render: (value: string | null) => value ?? '-' },
          { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value ?? '-' },
          {
            title: '申请时间',
            dataIndex: 'createdAt',
            render: (value: string) => new Date(value).toLocaleString('zh-CN'),
          },
          {
            title: '授予角色',
            key: 'role',
            render: (_, row) => (
              <Select<Role>
                aria-label={`授予 ${row.displayName ?? row.id} 的角色`}
                value={roleDraft[row.id] ?? 'MEMBER'}
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
                  type="primary"
                  onClick={() => void runAction(row.id, () => approveUser(row.id, roleDraft[row.id] ?? 'MEMBER'))}
                >
                  通过
                </Button>
                <Button danger onClick={() => void runAction(row.id, () => rejectUser(row.id))}>
                  拒绝
                </Button>
              </Space>
            ),
          },
        ]}
      />
    </Card>
  );
}
