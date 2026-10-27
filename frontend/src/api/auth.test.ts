import { describe, it, expect, afterEach } from 'vitest';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import client from './client';
import { logoutApi } from './auth';

/**
 * The sign-out call, through the real client: the adapter is replaced, so the interceptor chain runs and
 * nothing leaves the process.
 */
describe('logoutApi', () => {
  const realAdapter = client.defaults.adapter;

  afterEach(() => {
    client.defaults.adapter = realAdapter;
  });

  it('GivenASignedInUser_WhenTheySignOut_ThenTheCallCarriesTheHeaderACrossSiteFormCannotSet', async () => {
    // NFR-37: the server refuses a sign-out without it, so leaving it off would turn every sign-out into
    // the failure FR-48 says must leave the user signed in.
    const sent: InternalAxiosRequestConfig[] = [];
    const adapter: AxiosAdapter = (config) => {
      sent.push(config as InternalAxiosRequestConfig);
      return Promise.resolve({ data: '', status: 204, statusText: 'No Content', headers: {}, config });
    };
    client.defaults.adapter = adapter;

    await logoutApi();

    expect(sent).toHaveLength(1);
    expect(sent[0].method).toBe('post');
    expect(sent[0].url).toBe('/api/auth/logout');
    expect(sent[0].headers['X-Requested-With']).toBe('XMLHttpRequest');
  });
});
