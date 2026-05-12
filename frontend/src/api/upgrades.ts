import client from './client';
import type { HealthUpgrade, CreateUpgradeRequest, UpdateUpgradeStatusRequest, UpgradeStatus } from '../types';

export const getUpgrades = async (status?: UpgradeStatus): Promise<HealthUpgrade[]> => {
  const params = status ? { status } : {};
  const { data } = await client.get<HealthUpgrade[]>('/api/upgrades', { params });
  return data;
};

export const getUpgradeById = async (id: string): Promise<HealthUpgrade> => {
  const { data } = await client.get<HealthUpgrade>(`/api/upgrades/${id}`);
  return data;
};

export const createUpgrade = async (req: CreateUpgradeRequest): Promise<HealthUpgrade> => {
  const { data } = await client.post<HealthUpgrade>('/api/upgrades', req);
  return data;
};

export const updateUpgrade = async (id: string, req: Partial<CreateUpgradeRequest>): Promise<HealthUpgrade> => {
  const { data } = await client.put<HealthUpgrade>(`/api/upgrades/${id}`, req);
  return data;
};

export const updateUpgradeStatus = async (id: string, req: UpdateUpgradeStatusRequest): Promise<HealthUpgrade> => {
  const { data } = await client.patch<HealthUpgrade>(`/api/upgrades/${id}/status`, req);
  return data;
};

export const deleteUpgrade = async (id: string): Promise<void> => {
  await client.delete(`/api/upgrades/${id}`);
};
