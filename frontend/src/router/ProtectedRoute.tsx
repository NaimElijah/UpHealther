import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/ui/LoadingSpinner';

/** @param children the page to render once the caller is known to be signed in */
interface Props {
  children: React.ReactNode;
}

/**
 * Gate that renders its children only for a signed-in user.
 *
 * The loading state is not cosmetic: a stored token is verified asynchronously on load, and rendering
 * the redirect during that window would bounce a signed-in user to the login page on every refresh.
 */
const ProtectedRoute: React.FC<Props> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();
  if (isLoading) return <div className="min-h-screen bg-canvas flex items-center justify-center"><LoadingSpinner size="lg" /></div>;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <>{children}</>;
};

export default ProtectedRoute;
