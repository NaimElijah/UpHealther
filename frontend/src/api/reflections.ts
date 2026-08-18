import client from './client';
import type { Reflection, CreateReflectionRequest } from '../types';

/** Fetches an upgrade's reflections, newest first. */
export const getReflectionsByUpgrade = async (upgradeId: string): Promise<Reflection[]> => {
  const { data } = await client.get<Reflection[]>(`/api/upgrades/${upgradeId}/reflections`);
  return data;
};

/** Writes a reflection about an upgrade. There is no edit or delete counterpart. */
export const createReflection = async (
  upgradeId: string,
  req: Omit<CreateReflectionRequest, 'upgradeId'>,
): Promise<Reflection> => {
  const { data } = await client.post<Reflection>(`/api/upgrades/${upgradeId}/reflections`, req);
  return data;
};
