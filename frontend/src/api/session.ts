import { clearAccessToken } from './tokenStore';

/** What the layers above `api/` hand in to forget when a session ends; the query cache is the one. */
type SessionEndListener = () => void;

const sessionEndListeners = new Set<SessionEndListener>();

/**
 * Registers something to forget when a session in this tab ends, before the page navigates away.
 *
 * It exists because the query cache is built in `lib/`, which `api/` may not import (ADR-061), and a
 * navigation alone does not discard it: a page the back/forward cache keeps is restored with its script
 * state intact (S5.D1). `createQueryClient` registers the cache it builds.
 *
 * @param listener  called synchronously by `endSession`, once per call. That is not once per session:
 *                  parallel requests whose renewal fails each call it, so a listener must bear running
 *                  again
 * @returns a function that unregisters it
 */
export function onSessionEnd(listener: SessionEndListener): () => void {
  sessionEndListeners.add(listener);
  return () => sessionEndListeners.delete(listener);
}

/**
 * Ends the session in this tab: drops the access token, empties what `onSessionEnd` registered, and
 * sends the browser to the login page.
 *
 * The one way a session ends here, whether the user signed out, a renewal failed or the server said the
 * session had ended. It has to be a full document navigation, never a router push. Unloading the page
 * is what discards every provider's state, so nothing belonging to the previous user, their data or
 * `isAuthenticated: true`, can be read by whoever signs in next on the same machine. The query cache is
 * emptied and the application unmounted first as well, because the browser may keep the page it leaves
 * in its back/forward cache; the `pageshow` listener below reloads such a page if it is ever restored.
 *
 * It does not tell the server. A caller signing out awaits that first. The interceptor calls this once
 * a renewal fails, whether the server refused it or did not answer. The notification socket calls it
 * on the server's session-ended ERROR frame (ADR-066). The socket's own failed renewal does not: the
 * socket reconnects without a token, and the session ends at the tab's next request (ADR-054).
 *
 * @throws whatever a listener throws, once the navigation has been started regardless
 */
export function endSession(): void {
  clearAccessToken();
  try {
    sessionEndListeners.forEach((listener) => listener());
  } finally {
    // Whatever a listener threw, the tab still leaves the page that showed the session; the error
    // still propagates to the caller.
    window.location.href = '/login';
  }
}

// A page restored from the back/forward cache resumes the script state it was left with: the access
// token, the query cache and every provider's view of who is signed in, none of which has been checked
// since (S5.D1). Reloading it makes it start again from the refresh cookie, like any other load, at the
// cost of the instant Back the cache would have given.
window.addEventListener('pageshow', (event) => {
  if (event.persisted) window.location.reload();
});
