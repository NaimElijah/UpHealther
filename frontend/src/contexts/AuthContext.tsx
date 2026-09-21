import React, { useState, useEffect, useCallback } from 'react';
import { loginApi, registerApi, getMe, logoutApi } from '../api/auth';
import { renewSession } from '../api/client';
import { clearAccessToken, setAccessToken } from '../api/tokenStore';
import { AuthContext } from './authContextValue';
import type { User } from '../types';

/**
 * Keys this app used to write, when the token and a copy of the user lived in `localStorage`. Removed
 * on mount rather than left to rot: a stale token in storage is a credential sitting somewhere script
 * can read it, and it will still be there years after it stopped working.
 */
const LEGACY_KEYS = ['jwt_token', 'user'];

/**
 * Owns the session: the signed-in user and the sign-in, registration and sign-out actions.
 *
 * Nothing about the session is in web storage any more. The access token lives in memory
 * (`api/tokenStore`), so it dies with the tab; what survives a reload is the `HttpOnly` refresh cookie,
 * which this code cannot read and does not need to. A page load therefore starts signed out and asks
 * the server: one `POST /api/auth/refresh`, which either yields a fresh token or does not.
 *
 * That is why there is no optimistic restore from a cached user any more. There is nothing to restore
 * from, and the round trip that replaced it is the same one that used to validate the cached copy.
 *
 * A 401 on any later request is handled by the axios interceptor in `api/client.ts`, which renews the
 * token and retries before concluding anything.
 */
export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    LEGACY_KEYS.forEach((key) => {
      try {
        localStorage.removeItem(key);
      } catch {
        // Storage can be unavailable entirely — a private window, or blocked site data. There is
        // nothing to clean up in that case and nothing to report.
      }
    });

    let cancelled = false;

    // One request decides the whole question: a live refresh cookie means a session, and the absence
    // of one means an ordinary visitor. A 401 there is the expected answer for a visitor, not an
    // error, which is why renewSession reports it as false rather than throwing.
    renewSession()
      .then(async (renewed) => {
        if (!renewed) return;
        const freshUser = await getMe();
        if (!cancelled) {
          setUser(freshUser);
          setIsAuthenticated(true);
        }
      })
      .catch(() => {
        clearAccessToken();
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  /** Signs in and starts the session. Rejects if the credentials are wrong, leaving state untouched. */
  const login = useCallback(async (email: string, password: string) => {
    const response = await loginApi(email, password);
    setAccessToken(response.accessToken, response.expiresAt);
    setUser(response.user);
    setIsAuthenticated(true);
  }, []);

  /** Creates an account and signs straight in with the token it returns. */
  const register = useCallback(async (name: string, email: string, password: string) => {
    const response = await registerApi(name, email, password);
    setAccessToken(response.accessToken, response.expiresAt);
    setUser(response.user);
    setIsAuthenticated(true);
  }, []);

  /**
   * Ends the session on the server, then reloads onto the login page.
   *
   * The server call is awaited and its failure is propagated, not swallowed. Clearing local state on a
   * failed sign-out would be a lie: the refresh cookie would still be in the browser, so the next page
   * load would sign the user straight back in — on a shared machine, as far as they knew, after they
   * had signed out. The caller shows the failure and the user stays signed in, which is the honest
   * state.
   *
   * On success it is a full document navigation rather than a router push, so the query cache goes with
   * it and nothing belonging to the previous user can be read by the next one.
   */
  const logout = useCallback(async () => {
    await logoutApi();
    clearAccessToken();
    setUser(null);
    setIsAuthenticated(false);
    window.location.href = '/login';
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        login,
        register,
        logout,
        isAuthenticated,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};
