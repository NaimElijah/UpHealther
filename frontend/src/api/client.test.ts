import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from './client';

/**
 * The two interceptors on the shared axios instance, which are the whole reason `api/client.ts` exists
 * rather than each module calling axios directly.
 *
 * Neither is reachable by clicking through the application: one attaches a header, and the other fires
 * on a response the UI never renders. Both are load-bearing — the first is how every request is
 * authenticated, and the second is the only thing that ends a session whose token has expired.
 *
 * The adapter is replaced rather than the network stubbed, so the real interceptor chain runs and
 * nothing leaves the process. `window.location` is redefined because assigning to `href` in jsdom is a
 * navigation it refuses to perform.
 */
describe('the shared API client', () => {
  const realAdapter = client.defaults.adapter;
  const realLocation = window.location;
  let sentConfig: InternalAxiosRequestConfig | undefined;

  /** Replaces the adapter with one that records the outgoing request and answers with `respond`. */
  function withAdapter(respond: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>) {
    const adapter: AxiosAdapter = (config) => {
      sentConfig = config as InternalAxiosRequestConfig;
      return respond(config as InternalAxiosRequestConfig);
    };
    client.defaults.adapter = adapter;
  }

  function ok(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
    return Promise.resolve({ data: {}, status: 200, statusText: 'OK', headers: {}, config });
  }

  function failWith(status: number) {
    return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> =>
      Promise.reject(
        Object.assign(new Error(`Request failed with status code ${status}`), {
          isAxiosError: true,
          config,
          response: { data: {}, status, statusText: '', headers: {}, config },
        }),
      );
  }

  beforeEach(() => {
    sentConfig = undefined;
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...realLocation, href: '', assign: vi.fn() },
    });
  });

  afterEach(() => {
    client.defaults.adapter = realAdapter;
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  describe('attaching the stored token', () => {
    it('GivenAStoredToken_WhenARequestIsSent_ThenItCarriesTheBearerHeader', async () => {
      localStorage.setItem('jwt_token', 'stored.jwt.token');
      withAdapter(ok);

      await client.get('/api/upgrades');

      expect(sentConfig?.headers.Authorization).toBe('Bearer stored.jwt.token');
    });

    it('GivenNoStoredToken_WhenARequestIsSent_ThenItGoesOutAnonymously', async () => {
      // Registration and login are called before a token exists, so an anonymous request has to work.
      withAdapter(ok);

      await client.post('/api/auth/login', { email: 'someone@example.com', password: 's3cret!' });

      expect(sentConfig?.headers.Authorization).toBeUndefined();
    });

    it('GivenTheTokenChangesBetweenRequests_WhenTheSecondIsSent_ThenItCarriesTheNewOne', async () => {
      // The header is read per request rather than captured once, which is what makes a login take
      // effect without rebuilding the client.
      localStorage.setItem('jwt_token', 'first.token');
      withAdapter(ok);
      await client.get('/api/upgrades');
      expect(sentConfig?.headers.Authorization).toBe('Bearer first.token');

      localStorage.setItem('jwt_token', 'second.token');
      await client.get('/api/upgrades');

      expect(sentConfig?.headers.Authorization).toBe('Bearer second.token');
    });
  });

  describe('turning a 401 into a logout', () => {
    it('GivenAnExpiredToken_WhenTheServerAnswers401_ThenTheTokenIsClearedAndTheUserIsSentToLogin', async () => {
      localStorage.setItem('jwt_token', 'expired.token');
      withAdapter(failWith(401));

      await expect(client.get('/api/upgrades')).rejects.toBeDefined();

      expect(localStorage.getItem('jwt_token')).toBeNull();
      expect(window.location.href).toBe('/login');
    });

    it('GivenA401_WhenItIsHandled_ThenTheErrorIsStillRejectedRatherThanSwallowed', async () => {
      // The caller has to see the failure too: swallowing it would leave a component waiting on a
      // promise that never settles while the page navigates out from under it.
      withAdapter(failWith(401));

      await expect(client.get('/api/upgrades')).rejects.toMatchObject({ response: { status: 401 } });
    });

    it('GivenAServerError_WhenTheServerAnswers500_ThenTheSessionIsLeftAlone', async () => {
      // Only a 401 means "your token is no good". Logging the user out on any failure would end a
      // session because one request happened to fail.
      localStorage.setItem('jwt_token', 'still.valid.token');
      withAdapter(failWith(500));

      await expect(client.get('/api/upgrades')).rejects.toBeDefined();

      expect(localStorage.getItem('jwt_token')).toBe('still.valid.token');
      expect(window.location.href).toBe('');
    });

    it('GivenAForbiddenResponse_WhenTheServerAnswers403_ThenTheSessionIsLeftAlone', async () => {
      localStorage.setItem('jwt_token', 'still.valid.token');
      withAdapter(failWith(403));

      await expect(client.get('/api/upgrades')).rejects.toBeDefined();

      expect(localStorage.getItem('jwt_token')).toBe('still.valid.token');
    });

    it('GivenANetworkFailureWithNoResponse_WhenItIsHandled_ThenTheSessionIsLeftAlone', async () => {
      // `err.response` is undefined when the request never reached the server. Reading `.status` off
      // it unguarded would throw inside the interceptor and mask the real error.
      localStorage.setItem('jwt_token', 'still.valid.token');
      client.defaults.adapter = () => Promise.reject(new Error('Network Error'));

      await expect(client.get('/api/upgrades')).rejects.toThrow('Network Error');

      expect(localStorage.getItem('jwt_token')).toBe('still.valid.token');
    });
  });

  it('GivenNoApiUrlIsConfigured_WhenTheClientIsBuilt_ThenItsBaseUrlIsRelative', () => {
    // Same-origin by default is what routes every call through the Vite dev proxy and the nginx proxy
    // in production. An absolute default would need CORS configured to work at all.
    expect(client.defaults.baseURL).toBe('');
  });
});
