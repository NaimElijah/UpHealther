import React, { useState, useEffect, useCallback } from 'react';
import { loginApi, registerApi, getMe } from '../api/auth';
import { AuthContext } from './authContextValue';
import type { User } from '../types';

/**
 * Owns the session: the signed-in user, the token, and the login, register and logout actions.
 *
 * The token and a copy of the user live in `localStorage`, which is what survives a page reload. That
 * copy is treated as a cache, never as proof: on mount it is shown immediately so the UI has something
 * to render, and simultaneously checked against `/api/auth/me`. A token the server rejects clears the
 * session.
 *
 * A 401 on any later request is handled elsewhere, by the axios interceptor in `api/client.ts`.
 */
export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const storedToken = localStorage.getItem('jwt_token');
    const storedUser = localStorage.getItem('user');
    if (!storedToken) {
      setIsLoading(false);
      return;
    }

    setToken(storedToken);
    // Optimistically restore the cached user so the UI renders immediately...
    if (storedUser) {
      try {
        setUser(JSON.parse(storedUser) as User);
      } catch {
        localStorage.removeItem('user');
      }
    }

    // ...then validate the token against the backend. If it's expired/invalid, clear the session.
    getMe()
      .then((freshUser) => {
        setUser(freshUser);
        localStorage.setItem('user', JSON.stringify(freshUser));
      })
      .catch(() => {
        localStorage.removeItem('jwt_token');
        localStorage.removeItem('user');
        setToken(null);
        setUser(null);
      })
      .finally(() => setIsLoading(false));
  }, []);

  /** Signs in and persists the session. Rejects if the credentials are wrong, leaving state untouched. */
  const login = useCallback(async (email: string, password: string) => {
    const response = await loginApi(email, password);
    localStorage.setItem('jwt_token', response.token);
    localStorage.setItem('user', JSON.stringify(response.user));
    setToken(response.token);
    setUser(response.user);
  }, []);

  /** Creates an account and signs straight in with the token it returns. */
  const register = useCallback(async (name: string, email: string, password: string) => {
    const response = await registerApi(name, email, password);
    localStorage.setItem('jwt_token', response.token);
    localStorage.setItem('user', JSON.stringify(response.user));
    setToken(response.token);
    setUser(response.user);
  }, []);

  /**
   * Clears the session and reloads onto the login page.
   *
   * A full document navigation rather than a router push, deliberately: it also discards the query
   * cache, so nothing belonging to the previous user can be read by the next one.
   */
  const logout = useCallback(() => {
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('user');
    setToken(null);
    setUser(null);
    window.location.href = '/login';
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        isLoading,
        login,
        register,
        logout,
        isAuthenticated: !!token,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};
