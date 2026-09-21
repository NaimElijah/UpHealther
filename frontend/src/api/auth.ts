import client, { REQUESTED_WITH } from './client';
import type { AuthResponse, User } from '../types';

/** Exchanges credentials for an access token and profile. Rejects with a 401 when they do not match. */
export const loginApi = async (email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/login', { email, password });
  return data;
};

/** Creates an account and returns an access token for it. Rejects with a 422 if the email is taken. */
export const registerApi = async (name: string, email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/register', { name, email, password });
  return data;
};

/** Fetches the current user with the access token in memory. */
export const getMe = async (): Promise<User> => {
  const { data } = await client.get<User>('/api/auth/me');
  return data;
};

/**
 * Ends this session on the server and clears the refresh cookie.
 *
 * Rejects if the server could not be reached. That rejection matters and must not be swallowed: the
 * cookie would still be in the browser, so the next page load would silently sign the user back in
 * while they believed they had signed out.
 */
export const logoutApi = async (): Promise<void> => {
  await client.post('/api/auth/logout', null, {
    headers: { [REQUESTED_WITH]: 'XMLHttpRequest' },
  });
};
