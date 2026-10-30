import { clearAccessToken } from './tokenStore';

/**
 * Ends the session in this tab: drops the access token and sends the browser to the login page.
 *
 * The one way a session ends here, whether the user signed out or the server refused to renew it. It
 * has to be a full document navigation, never a router push. Unloading the page is what discards the
 * query cache and every provider's state, so nothing belonging to the previous user, their data or
 * `isAuthenticated: true`, can be read by whoever signs in next on the same machine.
 *
 * It does not tell the server. A caller signing out awaits that first, and the interceptor calls this
 * only once the server has refused the session.
 */
export function endSession(): void {
  clearAccessToken();
  window.location.href = '/login';
}
