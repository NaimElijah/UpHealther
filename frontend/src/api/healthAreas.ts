import client from './client';
import type { HealthArea, CreateHealthAreaRequest } from '../types';

export const getHealthAreas = async (): Promise<HealthArea[]> => {
  const { data } = await client.get<HealthArea[]>('/api/health-areas');
  return data;
};

export const createHealthArea = async (req: CreateHealthAreaRequest): Promise<HealthArea> => {
  const { data } = await client.post<HealthArea>('/api/health-areas', req);
  return data;
};

export const updateHealthArea = async (id: string, req: Partial<CreateHealthAreaRequest>): Promise<HealthArea> => {
  const { data } = await client.put<HealthArea>(`/api/health-areas/${id}`, req);
  return data;
};

export const deleteHealthArea = async (id: string): Promise<void> => {
  await client.delete(`/api/health-areas/${id}`);
};
