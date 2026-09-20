/**
 * The single axios instance every API module calls through.
 *
 * Its interceptors are the app's whole session behaviour: outgoing requests carry the in-memory access
 * token, and a 401 is treated as "this token lapsed" rather than "you are signed out" — the request is
 * renewed and retried once, and only a refresh that fails ends the session. Adding a second axios
 * instance elsewhere would bypass both.
 */
import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore';

// Default to a relative base URL so every `/api/...` call is same-origin and flows through the
// Vite dev proxy (vite.config.ts) in development and the nginx proxy (nginx.conf) in production.
// This avoids cross-origin/CORS issues. Set VITE_API_URL only when the API lives on another origin.
// withCredentials so the refresh cookie travels even when the API is on another origin; a same-origin
// request would send it regardless, and a deployment that split the origins would silently not.
const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
  withCredentials: true,
});

/**
 * The requests that exist to obtain a session. A 401 from one of these is an answer about the
 * credentials just submitted, which the form that sent them displays; it is not a session ending.
 */
const SESSION_ENTRY_PATHS = ['/api/auth/login', '/api/auth/register'];

const REFRESH_PATH = '/api/auth/refresh';

/**
 * The header the cookie endpoints require. It proves nothing about who is calling — it proves the call
 * was made by script, because a cross-site form post cannot set a header. See ADR-015.
 */
export const REQUESTED_WITH = 'X-Requested-With';

/** Held across tabs, so two windows waking together do not both rotate the refresh credential. */
const REFRESH_LOCK = 'healthupgrades.refresh';

/** How long to wait before the one retry after a 409, which means another tab rotated first. */
const CONFLICT_BACKOFF_MS = 150;

/** Set on a request that has already been retried once, so a second 401 is believed. */
type RetriableConfig = InternalAxiosRequestConfig & { retriedAfterRefresh?: boolean };

/** Attaches the in-memory access token to every outgoing request; anonymous when there is none. */
client.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/** The refresh in progress, if any. Every caller awaits the same one rather than starting another. */
let refreshInFlight: Promise<boolean> | null = null;

/**
 * Renews the access token from the refresh cookie, at most once at a time.
 *
 * Single-flight twice over. Within this tab, concurrent callers share one promise — otherwise five
 * parallel 401s would rotate the credential five times and four of them would be refused. Across tabs,
 * `navigator.locks` serialises the same thing, because rotation makes a credential good for exactly one
 * use and two tabs waking together would each invalidate the other's.
 *
 * Exported because restoring a session on page load is the same operation: the tab starts with no token
 * and asks the cookie for one. Routing it through here rather than a second call site means the mount-
 * time restore shares the lock with everything else.
 *
 * @returns whether there is now a usable access token
 */
export function renewSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = withTabLock(() => refreshOnce()).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/**
 * Serialises work across tabs where the Web Locks API exists, and runs it directly where it does not.
 *
 * Absence is a degradation, not a failure: without the lock two tabs can collide, and a collision is
 * answered 409 and retried below. The lock turns a common case into a rare one.
 */
async function withTabLock<T>(work: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request(REFRESH_LOCK, work) as Promise<T>;
  }
  return work();
}

/**
 * One exchange of the refresh cookie, with a single retry when another tab beat us to it.
 *
 * A 409 means the credential was rotated moments ago and this request arrived holding the superseded
 * one — the cookie has since been replaced, so the same request again succeeds. Anything else means the
 * session is over. The call goes through this client on purpose: the response interceptor below leaves
 * the refresh path alone, so it cannot recurse into itself.
 */
async function refreshOnce(retryOnConflict = true): Promise<boolean> {
  try {
    const { data } = await client.post(REFRESH_PATH, null, {
      headers: { [REQUESTED_WITH]: 'XMLHttpRequest' },
    });
    setAccessToken(data.accessToken, data.expiresAt);
    return true;
  } catch (error) {
    // axios.isAxiosError rather than instanceof: the guard axios documents, and the only one that holds
    // when more than one copy of axios ends up in a bundle and the class identities differ.
    if (retryOnConflict && axios.isAxiosError(error) && error.response?.status === 409) {
      await delay(CONFLICT_BACKOFF_MS);
      return refreshOnce(false);
    }
    clearAccessToken();
    return false;
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Turns a 401 into a renewal, and only a failed renewal into a sign-out.
 *
 * The access token is short-lived by design, so a 401 on an ordinary request is the expected end of its
 * life rather than evidence of anything. The request is retried once with a fresh token and the user
 * sees nothing. Only when the refresh cookie is gone or refused is the session actually over, and then
 * the page is replaced — a hard navigation rather than a router push, because it also drops any cached
 * server state belonging to the old session.
 *
 * Left alone: a sign-in or registration attempt, whose 401 belongs to the form; the refresh call
 * itself, which would otherwise recurse; and any request already retried once, or a server answering
 * 401 to everything would loop.
 */
client.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined;
    const url = config?.url ?? '';
    const isRenewable =
      error.response?.status === 401 &&
      config !== undefined &&
      !config.retriedAfterRefresh &&
      url !== REFRESH_PATH &&
      !SESSION_ENTRY_PATHS.includes(url);

    if (!isRenewable) {
      return Promise.reject(error);
    }

    config.retriedAfterRefresh = true;
    if (await renewSession()) {
      return client(config);
    }

    endSession();
    return Promise.reject(error);
  },
);

/** Drops the token and sends the browser to the login page, discarding the current document with it. */
function endSession(): void {
  clearAccessToken();
  window.location.href = '/login';
}

export default client;
