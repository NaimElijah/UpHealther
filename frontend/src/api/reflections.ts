import client from './client';
import type { Reflection, CreateReflectionRequest } from '../types';

export const getReflectionsByUpgrade = async (upgradeId: string): Promise<Reflection[]> => {
  const { data } = await client.get<Reflection[]>(`/api/reflections/upgrade/${upgradeId}`);
  return data;
};

export const createReflection = async (req: CreateReflectionRequest): Promise<Reflection> => {
  const { data } = await client.post<Reflection>('/api/reflections', req);
  return data;
};
