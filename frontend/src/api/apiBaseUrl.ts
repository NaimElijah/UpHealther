/**
 * The API's base URL: `VITE_API_URL` when it is set, otherwise empty, so that every call is same-origin
 * and goes through the Vite dev proxy or the nginx proxy.
 *
 * The one place the API's origin is decided. The axios instance and the notification socket both take
 * it from here, so a change to the rule reaches HTTP and the live notifications together.
 *
 * @returns the configured base URL, or `''` when the API is on the page's own origin
 */
export function apiBaseUrl(): string {
  return import.meta.env.VITE_API_URL || '';
}
