import { createContext } from 'react';
import type { User } from '../types';

/**
 * What the auth context exposes: the signed-in user and the session actions.
 *
 * There is deliberately no `token` here. The access token lives in `api/tokenStore` and is read by the
 * axios interceptors; putting it on the context would hand it to every component that wanted the user's
 * name, and would put it in React state where a devtools snapshot or an error reporter can pick it up.
 * A component needing an authenticated request calls through `api/client`, which attaches it.
 *
 * `isLoading` is true only while the session is being restored from the refresh cookie on load;
 * `isAuthenticated` is false during that window, which is why consumers must check the former before
 * acting on the latter.
 */
export interface AuthContextType {
  user: User | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string) => Promise<void>;
  /** Rejects if the server could not end the session — see `AuthContext` for why that must be shown. */
  logout: () => Promise<void>;
  isAuthenticated: boolean;
}

/**
 * The context object itself, kept in its own module so the file holding the provider component exports
 * only components — which is what lets React Fast Refresh work on it.
 */
export const AuthContext = createContext<AuthContextType | null>(null);
