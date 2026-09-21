/**
 * The access token, held in the tab's memory and nowhere else.
 *
 * Deliberately not `localStorage`, `sessionStorage` or a cookie the page can read. Anything injected
 * into this page — a compromised dependency, a bad extension — can read web storage, and whatever it
 * finds there it can copy somewhere that outlives the tab. A module variable dies with the tab, and the
 * token it held expires minutes later anyway.
 *
 * What survives a reload is the refresh cookie, which is `HttpOnly`: this file cannot read it either,
 * and that is the point. A page load therefore starts with no token and asks the server for one.
 *
 * A module holding mutable state rather than React state, because the axios interceptors need to read
 * it and they run outside React. Nothing here renders, so nothing here belongs in a component.
 */

/** The current token, or null when there is none — before sign-in, and immediately after a reload. */
let accessToken: string | null = null;

/** When the current token stops being accepted, as epoch milliseconds. */
let expiresAtMs: number | null = null;

/**
 * How far ahead of expiry a token is already treated as expired.
 *
 * A token that lapses in two seconds will lapse in transit, so a request carrying it is a wasted round
 * trip and a retry. Renewing early costs one refresh and hides the boundary from the user.
 */
const EXPIRY_MARGIN_MS = 30_000;

/** Stores a freshly issued token. */
export function setAccessToken(token: string, expiresAt: string): void {
  accessToken = token;
  const parsed = Date.parse(expiresAt);
  expiresAtMs = Number.isNaN(parsed) ? null : parsed;
}

/** Forgets the current token. The session may still be alive; only this credential is gone. */
export function clearAccessToken(): void {
  accessToken = null;
  expiresAtMs = null;
}

/** The current token, or null when there is none to send. */
export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * Whether there is a token worth sending: one that exists and is not about to lapse.
 *
 * An unknown expiry counts as usable — the token was issued by this server, and being refused is a
 * recoverable outcome, whereas refusing to send it at all is not.
 */
export function hasUsableAccessToken(now: number = Date.now()): boolean {
  if (accessToken === null) return false;
  if (expiresAtMs === null) return true;
  return now < expiresAtMs - EXPIRY_MARGIN_MS;
}
