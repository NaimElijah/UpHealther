import client from './client';
import type { AuthResponse } from '../types';

export const loginApi = async (email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/login', { email, password });
  return data;
};

export const registerApi = async (name: string, email: string, password: string): Promise<AuthResponse> => {
  const { data } = await client.post<AuthResponse>('/api/auth/register', { name, email, password });
  return data;
};
