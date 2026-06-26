import client from './client';
import type { Reflection, CreateReflectionRequest } from '../types';

export const getReflectionsByUpgrade = async (upgradeId: string): Promise<Reflection[]> => {
  const { data } = await client.get<Reflection[]>(`/api/upgrades/${upgradeId}/reflections`);
  return data;
};

export const createReflection = async (
  upgradeId: string,
  req: Omit<CreateReflectionRequest, 'upgradeId'>,
): Promise<Reflection> => {
  const { data } = await client.post<Reflection>(`/api/upgrades/${upgradeId}/reflections`, req);
  return data;
};
