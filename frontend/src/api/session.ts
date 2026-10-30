import { clearAccessToken } from './tokenStore';

/**
 * Ends the session in this tab: drops the access token and sends the browser to the login page.
 *
 * The one way a session ends here, whether the user signed out or a renewal failed. It has to be a
 * full document navigation, never a router push. Unloading the page is what discards the
 * query cache and every provider's state, so nothing belonging to the previous user, their data or
 * `isAuthenticated: true`, can be read by whoever signs in next on the same machine.
 *
 * It does not tell the server. A caller signing out awaits that first. The interceptor calls this once
 * a renewal fails, whether the server refused it or did not answer. The notification socket's own
 * failed renewal does not call it: the socket reconnects without a token, and the session ends at the
 * tab's next request (ADR-054).
 */
export function endSession(): void {
  clearAccessToken();
  window.location.href = '/login';
}
