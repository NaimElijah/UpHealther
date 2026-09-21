import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import RequireRole from './RequireRole';
import Layout from '../components/layout/Layout';
import { NotificationProvider } from '../contexts/NotificationProvider';
import LoginPage from '../pages/LoginPage';
import RegisterPage from '../pages/RegisterPage';
import DashboardPage from '../pages/DashboardPage';
import HealthAreasPage from '../pages/HealthAreasPage';
import UpgradeBacklogPage from '../pages/UpgradeBacklogPage';
import ActiveUpgradesPage from '../pages/ActiveUpgradesPage';
import PlannedUpgradesPage from '../pages/PlannedUpgradesPage';
import UpgradeDetailsPage from '../pages/UpgradeDetailsPage';
import DailyCheckinPage from '../pages/DailyCheckinPage';
import ProgressHistoryPage from '../pages/ProgressHistoryPage';
import NotificationsPage from '../pages/NotificationsPage';
import AdminUsersPage from '../pages/AdminUsersPage';

/**
 * The route table, and the only place a URL maps to a page.
 *
 * Every route but login and register is wrapped in {@link ProtectedRoute} and the shared {@link Layout},
 * so an authenticated page cannot be added without both. The administration page adds
 * {@link RequireRole} on top, which is a courtesy rather than a wall — the API decides. Unknown paths
 * fall through to `/`, which redirects to the dashboard when signed in and to login otherwise.
 */
// NotificationProvider lives inside the Router so toasts/items can navigate, and inside the existing
// QueryClientProvider + AuthProvider (mounted in App.tsx) for query + auth access.
const AppRouter: React.FC = () => (
  <BrowserRouter>
    <NotificationProvider>
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <Navigate to="/dashboard" replace />
          </ProtectedRoute>
        }
      />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <Layout><DashboardPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/health-areas"
        element={
          <ProtectedRoute>
            <Layout><HealthAreasPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/upgrades/backlog"
        element={
          <ProtectedRoute>
            <Layout><UpgradeBacklogPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/upgrades/active"
        element={
          <ProtectedRoute>
            <Layout><ActiveUpgradesPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/upgrades/planned"
        element={
          <ProtectedRoute>
            <Layout><PlannedUpgradesPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/upgrades/:id"
        element={
          <ProtectedRoute>
            <Layout><UpgradeDetailsPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/daily-checkin"
        element={
          <ProtectedRoute>
            <Layout><DailyCheckinPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/progress-history"
        element={
          <ProtectedRoute>
            <Layout><ProgressHistoryPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/notifications"
        element={
          <ProtectedRoute>
            <Layout><NotificationsPage /></Layout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/users"
        element={
          // RequireRole inside ProtectedRoute, not instead of it: an anonymous visitor is sent to
          // sign in, and a signed-in non-administrator to their dashboard. The API refuses the
          // requests behind this either way — the gate only saves somebody a page of failures.
          <ProtectedRoute>
            <RequireRole role="ADMIN">
              <Layout><AdminUsersPage /></Layout>
            </RequireRole>
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </NotificationProvider>
  </BrowserRouter>
);

export default AppRouter;
