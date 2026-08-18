import client from './client';
import type { HealthArea, CreateHealthAreaRequest } from '../types';

/** Lists the caller's health areas. */
export const getHealthAreas = async (): Promise<HealthArea[]> => {
  const { data } = await client.get<HealthArea[]>('/api/health-areas');
  return data;
};

/** Creates a health area. */
export const createHealthArea = async (req: CreateHealthAreaRequest): Promise<HealthArea> => {
  const { data } = await client.post<HealthArea>('/api/health-areas', req);
  return data;
};

/** Replaces a health area's attributes; omitted fields are cleared, not preserved. */
export const updateHealthArea = async (id: string, req: Partial<CreateHealthAreaRequest>): Promise<HealthArea> => {
  const { data } = await client.put<HealthArea>(`/api/health-areas/${id}`, req);
  return data;
};

/** Deletes a health area. Upgrades filed under it keep the now-dangling area id. */
export const deleteHealthArea = async (id: string): Promise<void> => {
  await client.delete(`/api/health-areas/${id}`);
};
