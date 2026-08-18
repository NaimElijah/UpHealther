import client from './client';
import type { AuthResponse, User } from '../types';

/** Exchanges credentials for a token and profile. Rejects with a 401 when they do not match. */
export const loginApi = async (email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/login', { email, password });
  return data;
};

/** Creates an account and returns a token for it. Rejects with a 422 if the email is taken. */
export const registerApi = async (name: string, email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/register', { name, email, password });
  return data;
};

/** Fetches the current user from the stored token — how a session is restored on a page load. */
export const getMe = async (): Promise<User> => {
  const { data } = await client.get<User>('/api/auth/me');
  return data;
};
