import { request } from './client';
import type { Role, UserStatus } from './auth';

export type UserSummary = {
  id: number;
  displayName: string | null;
  email: string | null;
  avatarUrl: string | null;
  role: Role;
  status: UserStatus;
  createdAt: string;
  lastLoginAt: string | null;
};

export function listUsers(status: UserStatus): Promise<UserSummary[]> {
  return request<UserSummary[]>(`/api/admin/users?status=${status}`);
}

export function approveUser(id: number, role: Role): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/approve`, { method: 'POST', body: { role } });
}

export function rejectUser(id: number): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/reject`, { method: 'POST' });
}

export function changeUserRole(id: number, role: Role): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/role`, { method: 'POST', body: { role } });
}

export function disableUser(id: number): Promise<UserSummary> {
  return request<UserSummary>(`/api/admin/users/${id}/disable`, { method: 'POST' });
}
