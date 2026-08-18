import client from './client';
import type { DashboardDto } from '../types';

/** Fetches the whole dashboard in one request; every section is computed server-side. */
export const getDashboard = async (): Promise<DashboardDto> => {
  const { data } = await client.get<DashboardDto>('/api/dashboard');
  return data;
};
