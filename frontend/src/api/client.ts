/**
 * The single axios instance every API module calls through.
 *
 * Two interceptors give it the app's auth behaviour: outgoing requests carry the stored JWT, and a 401
 * response ends the session and sends the user to the login page. Adding a second axios instance
 * elsewhere would bypass both.
 */
import axios from 'axios';

// Default to a relative base URL so every `/api/...` call is same-origin and flows through the
// Vite dev proxy (vite.config.ts) in development and the nginx proxy (nginx.conf) in production.
// This avoids cross-origin/CORS issues. Set VITE_API_URL only when the API lives on another origin.
const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
});

/**
 * The requests that exist to obtain a session. A 401 from one of these is an answer about the
 * credentials just submitted, which the form that sent them displays; it is not a session ending.
 */
const SESSION_ENTRY_PATHS = ['/api/auth/login', '/api/auth/register'];

/** Attaches the stored JWT to every outgoing request; anonymous when there is none. */
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('jwt_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Turns a 401 into a logout: the server no longer accepts the token — missing, expired or refused, it
 * answers all three the same way — so clear the session and go to the login page.
 *
 * A hard `location.href` assignment rather than a router navigation, deliberately — this runs outside
 * React, and replacing the document also drops any cached server state belonging to the old session.
 *
 * Not for a sign-in or registration attempt: their 401 belongs to the form. A 403 is left alone too;
 * it means the caller is known and not allowed, and the session is fine.
 */
client.interceptors.response.use(
  (r) => r,
  (err) => {
    const isSessionEntry = SESSION_ENTRY_PATHS.includes(err.config?.url ?? '');
    if (err.response?.status === 401 && !isSessionEntry) {
      localStorage.removeItem('jwt_token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export default client;
