import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import type { UserRole } from '../types';

/**
 * @param role     the role the caller must hold
 * @param children the page to render once they are known to hold it
 */
interface Props {
  role: UserRole;
  children: React.ReactNode;
}

/**
 * Gate that renders its children only for a signed-in user holding a particular role.
 *
 * **This is not what enforces anything.** Every path behind it answers 403 on its own, decided by the
 * role read from the account on that request. This gate exists so somebody who is not an administrator
 * gets the dashboard instead of a page of failed requests — a courtesy, and it is worth being explicit
 * that it is only a courtesy, because a gate that looks like a wall invites somebody to lean on it.
 *
 * Wrapped *inside* `ProtectedRoute` at the route, so an anonymous visitor is sent to sign in rather
 * than silently bounced to a dashboard they also cannot see.
 *
 * The loading state matters for the same reason it does in `ProtectedRoute`: the session is restored
 * asynchronously on load, and redirecting during that window would bounce an administrator off their
 * own page on every refresh.
 */
const RequireRole: React.FC<Props> = ({ role, children }) => {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-canvas flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }
  if (user?.role !== role) return <Navigate to="/dashboard" replace />;
  return <>{children}</>;
};

export default RequireRole;
