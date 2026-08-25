/**
 * The single axios instance every API module calls through.
 *
 * Two interceptors give it the app's auth behaviour: outgoing requests carry the stored JWT, and a 401
 * response clears the token and sends the user to the login page. Adding a second axios instance
 * elsewhere would bypass both.
 *
 * An expired token does not reach that second interceptor — the API refuses it with 403. See the
 * interceptor's own comment below.
 */
import axios from 'axios';

// Default to a relative base URL so every `/api/...` call is same-origin and flows through the
// Vite dev proxy (vite.config.ts) in development and the nginx proxy (nginx.conf) in production.
// This avoids cross-origin/CORS issues. Set VITE_API_URL only when the API lives on another origin.
const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
});

/** Attaches the stored JWT to every outgoing request; anonymous when there is none. */
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('jwt_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Turns a 401 into a logout: the server has rejected the token, so clear it and go to the login page.
 *
 * A hard `location.href` assignment rather than a router navigation, deliberately — this runs outside
 * React, and replacing the document also drops any cached server state belonging to the old session.
 *
 * It does not fire on expiry, which is the case it reads as though it covers. The API configures no
 * `AuthenticationEntryPoint`, so Spring Security refuses an anonymous request — and an expired token
 * leaves the request anonymous — with 403 rather than 401 (see `AuthenticatedBoundaryTest`). A page
 * load still recovers, because `AuthContext` clears the session on any failed `/api/auth/me`; a tab
 * left open across the 24-hour expiry does not. Which side moves is tracked in #58.
 */
client.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('jwt_token');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export default client;
