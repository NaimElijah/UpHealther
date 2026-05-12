import client from './client';
import type { DashboardDto } from '../types';

export const getDashboard = async (): Promise<DashboardDto> => {
  const { data } = await client.get<DashboardDto>('/api/dashboard');
  return data;
};
