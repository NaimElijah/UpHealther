import { useContext } from 'react';
import { AuthContext } from '../contexts/authContextValue';

/**
 * Reads the auth context.
 *
 * @throws Error when called outside `AuthProvider` — a null context would otherwise surface much later
 * as an unexplained "cannot read property of null" inside a component.
 */
export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
};
