import client from './client';
import type { ProgressEntry, CreateProgressRequest } from '../types';

export const getProgressByUpgrade = async (upgradeId: string): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>(`/api/progress/upgrade/${upgradeId}`);
  return data;
};

export const getAllProgress = async (): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>('/api/progress');
  return data;
};

export const createProgress = async (req: CreateProgressRequest): Promise<ProgressEntry> => {
  const { data } = await client.post<ProgressEntry>('/api/progress', req);
  return data;
};

export const getStreak = async (upgradeId: string): Promise<number> => {
  const { data } = await client.get<number>(`/api/progress/upgrade/${upgradeId}/streak`);
  return data;
};
