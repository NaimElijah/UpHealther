import { createContext } from 'react';
import type { User } from '../types';

/**
 * What the auth context exposes: the signed-in user, the raw token, and the session actions.
 *
 * `isLoading` is true only while a stored token is being verified on load; `isAuthenticated` is false
 * during that window, which is why consumers must check the former before acting on the latter.
 */
export interface AuthContextType {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string) => Promise<void>;
  logout: () => void;
  isAuthenticated: boolean;
}

/**
 * The context object itself, kept in its own module so the file holding the provider component exports
 * only components — which is what lets React Fast Refresh work on it.
 */
export const AuthContext = createContext<AuthContextType | null>(null);
