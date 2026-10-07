import { Navigate, Route, Routes } from 'react-router-dom';

import { RequireAuth } from './RequireAuth';
import { AppLayout } from '../layout/AppLayout';
import { ApprovalsPage } from '../pages/ApprovalsPage';
import { AuthCallbackPage } from '../pages/AuthCallbackPage';
import { DashboardPage } from '../pages/DashboardPage';
import { LocalLoginPage } from '../pages/LocalLoginPage';
import { LoginPage } from '../pages/LoginPage';
import { PendingPage } from '../pages/PendingPage';
import { RejectedPage } from '../pages/RejectedPage';
import { UsersPage } from '../pages/UsersPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/login/local" element={<LocalLoginPage />} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />

      <Route
        path="/pending"
        element={
          <RequireAuth allow={['pending']}>
            <PendingPage />
          </RequireAuth>
        }
      />
      <Route
        path="/rejected"
        element={
          <RequireAuth allow={['rejected']}>
            <RejectedPage />
          </RequireAuth>
        }
      />

      <Route
        path="/"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route
          path="admin/approvals"
          element={
            <RequireAuth role="ADMIN">
              <ApprovalsPage />
            </RequireAuth>
          }
        />
        <Route
          path="admin/users"
          element={
            <RequireAuth role="ADMIN">
              <UsersPage />
            </RequireAuth>
          }
        />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
