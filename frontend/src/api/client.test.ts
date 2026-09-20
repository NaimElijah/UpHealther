import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from './client';
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore';

/**
 * The two interceptors on the shared axios instance, which are the whole reason `api/client.ts` exists
 * rather than each module calling axios directly.
 *
 * Neither is reachable by clicking through the application: one attaches a header, and the other fires
 * on a response the UI never renders. Both are load-bearing, and the second one now does something
 * subtle enough to be worth pinning line by line. A 401 is the *expected* end of a fifteen-minute
 * token's life, so it means "renew and try again", not "you are signed out". Getting that wrong in one
 * direction signs people out every fifteen minutes; in the other it retries forever against a server
 * that is refusing everything.
 *
 * The adapter is replaced rather than the network stubbed, so the real interceptor chain runs and
 * nothing leaves the process. `window.location` is redefined because assigning to `href` in jsdom is a
 * navigation it refuses to perform.
 */
describe('the shared API client', () => {
  const realAdapter = client.defaults.adapter;
  const realLocation = window.location;
  let sentConfigs: InternalAxiosRequestConfig[] = [];

  /** Replaces the adapter with one that records every outgoing request and answers with `respond`. */
  function withAdapter(respond: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>) {
    const adapter: AxiosAdapter = (config) => {
      sentConfigs.push(config as InternalAxiosRequestConfig);
      return respond(config as InternalAxiosRequestConfig);
    };
    client.defaults.adapter = adapter;
  }

  function ok(config: InternalAxiosRequestConfig, data: unknown = {}): Promise<AxiosResponse> {
    return Promise.resolve({ data, status: 200, statusText: 'OK', headers: {}, config });
  }

  function failWith(status: number) {
    return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => rejection(config, status);
  }

  function rejection(config: InternalAxiosRequestConfig, status: number): Promise<AxiosResponse> {
    return Promise.reject(
      Object.assign(new Error(`Request failed with status code ${status}`), {
        isAxiosError: true,
        name: 'AxiosError',
        config,
        response: { data: {}, status, statusText: '', headers: {}, config },
      }),
    );
  }

  /** A refreshed session, as the server would answer it. */
  const RENEWED = { accessToken: 'renewed.jwt.token', expiresAt: '2099-01-01T00:00:00Z' };

  const lastSent = () => sentConfigs[sentConfigs.length - 1];
  const pathsSent = () => sentConfigs.map((c) => c.url);

  beforeEach(() => {
    sentConfigs = [];
    clearAccessToken();
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

  describe('attaching the in-memory token', () => {
    it('GivenATokenInMemory_WhenARequestIsSent_ThenItCarriesTheBearerHeader', async () => {
      setAccessToken('stored.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter(ok);

      await client.get('/api/upgrades');

      expect(lastSent().headers.Authorization).toBe('Bearer stored.jwt.token');
    });

    it('GivenNoTokenInMemory_WhenARequestIsSent_ThenItGoesOutAnonymously', async () => {
      // Registration and login are called before a token exists, so an anonymous request has to work.
      withAdapter(ok);

      await client.post('/api/auth/login', { email: 'someone@example.com', password: 's3cret!' });

      expect(lastSent().headers.Authorization).toBeUndefined();
    });

    it('GivenTheTokenChangesBetweenRequests_WhenTheSecondIsSent_ThenItCarriesTheNewOne', async () => {
      // Read per request, not captured once. A token renewed underneath a long-lived caller must be
      // the one the next request sends.
      setAccessToken('first.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter(ok);
      await client.get('/api/upgrades');

      setAccessToken('second.jwt.token', '2099-01-01T00:00:00Z');
      await client.get('/api/upgrades');

      expect(lastSent().headers.Authorization).toBe('Bearer second.jwt.token');
    });
  });

  describe('renewing on a 401 instead of signing out', () => {
    it('GivenAnExpiredToken_WhenARequestIs401_ThenItIsRenewedAndTheRequestRetriedWithTheNewToken', async () => {
      setAccessToken('expired.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter((config) => {
        if (config.url === '/api/auth/refresh') return ok(config, RENEWED);
        // The first attempt carries the expired token; the retry carries the renewed one.
        return config.headers.Authorization === 'Bearer renewed.jwt.token'
          ? ok(config, { ok: true })
          : rejection(config, 401);
      });

      const response = await client.get('/api/upgrades');

      expect(response.data).toEqual({ ok: true });
      expect(pathsSent()).toEqual(['/api/upgrades', '/api/auth/refresh', '/api/upgrades']);
      expect(window.location.href).toBe('');
    });

    it('GivenNoLiveSession_WhenTheRenewalIsAlsoRefused_ThenTheSessionEndsAndTheUserIsSentToLogin', async () => {
      setAccessToken('expired.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter(failWith(401));

      await expect(client.get('/api/upgrades')).rejects.toMatchObject({ response: { status: 401 } });

      expect(window.location.href).toBe('/login');
      expect(getAccessToken()).toBeNull();
    });

    it('GivenAnotherTabRotatedFirst_WhenTheRenewalIs409_ThenItIsTriedOnceMoreAndSucceeds', async () => {
      // Two tabs woke together. The 409 says the cookie has just been replaced, so the same request
      // again carries the new one.
      setAccessToken('expired.jwt.token', '2099-01-01T00:00:00Z');
      let refreshAttempts = 0;
      withAdapter((config) => {
        if (config.url === '/api/auth/refresh') {
          refreshAttempts += 1;
          return refreshAttempts === 1 ? rejection(config, 409) : ok(config, RENEWED);
        }
        return config.headers.Authorization === 'Bearer renewed.jwt.token'
          ? ok(config, { ok: true })
          : rejection(config, 401);
      });

      await client.get('/api/upgrades');

      expect(refreshAttempts).toBe(2);
      expect(window.location.href).toBe('');
    });

    it('GivenSeveralRequestsFailTogether_WhenTheyAreRenewed_ThenTheCredentialIsRotatedOnlyOnce', async () => {
      // Rotation makes a credential good for exactly one use, so a burst of 401s must share one
      // refresh. Without the single flight, four of five would be refused and end the session.
      setAccessToken('expired.jwt.token', '2099-01-01T00:00:00Z');
      let refreshAttempts = 0;
      withAdapter((config) => {
        if (config.url === '/api/auth/refresh') {
          refreshAttempts += 1;
          return ok(config, RENEWED);
        }
        return config.headers.Authorization === 'Bearer renewed.jwt.token'
          ? ok(config, { ok: true })
          : rejection(config, 401);
      });

      await Promise.all([
        client.get('/api/upgrades'),
        client.get('/api/health-areas'),
        client.get('/api/notifications'),
      ]);

      expect(refreshAttempts).toBe(1);
    });

    it('GivenAServerRefusingEverything_WhenARequestIs401Twice_ThenItIsNotRetriedForever', async () => {
      setAccessToken('expired.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter((config) =>
        config.url === '/api/auth/refresh' ? ok(config, RENEWED) : rejection(config, 401),
      );

      await expect(client.get('/api/upgrades')).rejects.toMatchObject({ response: { status: 401 } });

      expect(pathsSent()).toEqual(['/api/upgrades', '/api/auth/refresh', '/api/upgrades']);
    });
  });

  describe('leaving the session alone', () => {
    it('GivenALoginTheServerRejects_WhenItAnswers401_ThenNothingIsRenewedAndTheFormSeesTheError', async () => {
      withAdapter(failWith(401));

      await expect(
        client.post('/api/auth/login', { email: 'someone@example.com', password: 'wrong' }),
      ).rejects.toMatchObject({ response: { status: 401 } });

      expect(pathsSent()).toEqual(['/api/auth/login']);
      expect(window.location.href).toBe('');
    });

    it('GivenARegistrationTheServerRejects_WhenItAnswers401_ThenNothingIsRenewed', async () => {
      withAdapter(failWith(401));

      await expect(client.post('/api/auth/register', {})).rejects.toMatchObject({
        response: { status: 401 },
      });

      expect(pathsSent()).toEqual(['/api/auth/register']);
    });

    it('GivenAServerError_WhenTheServerAnswers500_ThenTheSessionIsLeftAlone', async () => {
      setAccessToken('stored.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter(failWith(500));

      await expect(client.get('/api/upgrades')).rejects.toMatchObject({ response: { status: 500 } });

      expect(window.location.href).toBe('');
      expect(getAccessToken()).toBe('stored.jwt.token');
    });

    it('GivenAForbiddenResponse_WhenTheServerAnswers403_ThenTheSessionIsLeftAlone', async () => {
      // 403 means known and not allowed — an ordinary user reaching an administration path. Renewing
      // would not change the answer, and signing them out would be nonsense.
      setAccessToken('stored.jwt.token', '2099-01-01T00:00:00Z');
      withAdapter(failWith(403));

      await expect(client.get('/api/admin/users')).rejects.toMatchObject({ response: { status: 403 } });

      expect(pathsSent()).toEqual(['/api/admin/users']);
      expect(window.location.href).toBe('');
    });

    it('GivenANetworkFailureWithNoResponse_WhenItIsHandled_ThenTheSessionIsLeftAlone', async () => {
      setAccessToken('stored.jwt.token', '2099-01-01T00:00:00Z');
      client.defaults.adapter = () => Promise.reject(new Error('Network Error'));

      await expect(client.get('/api/upgrades')).rejects.toThrow('Network Error');

      expect(window.location.href).toBe('');
      expect(getAccessToken()).toBe('stored.jwt.token');
    });
  });

  it('GivenNoApiUrlIsConfigured_WhenTheClientIsBuilt_ThenItsBaseUrlIsRelative', () => {
    // Relative means same-origin, which is what puts every call through the Vite or nginx proxy and
    // is why the refresh cookie arrives at all.
    expect(client.defaults.baseURL).toBe('');
  });

  it('GivenTheClientIsBuilt_WhenItSendsARequest_ThenItCarriesCredentials', () => {
    // Same-origin requests would send the cookie regardless; a deployment that split the origins
    // would silently not, and the session would end at every reload.
    expect(client.defaults.withCredentials).toBe(true);
  });
});
