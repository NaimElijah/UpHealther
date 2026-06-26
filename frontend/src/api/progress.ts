import client from './client';
import type { ProgressEntry, CreateProgressRequest, StreakSummary } from '../types';

export const getProgressByUpgrade = async (upgradeId: string): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>(`/api/upgrades/${upgradeId}/progress`);
  return data;
};

export const getWeekProgress = async (): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>('/api/progress/week');
  return data;
};

export const getTodayProgress = async (): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>('/api/progress/today');
  return data;
};

export const createProgress = async (req: CreateProgressRequest): Promise<ProgressEntry> => {
  const { upgradeId, date, completed, numericValue, unit, rating, note } = req;
  const { data } = await client.post<ProgressEntry>(`/api/upgrades/${upgradeId}/progress`, {
    date,
    completed,
    numericValue,
    unit,
    rating,
    note,
  });
  return data;
};

export const getStreak = async (upgradeId: string): Promise<StreakSummary> => {
  const { data } = await client.get<StreakSummary>(`/api/upgrades/${upgradeId}/streak`);
  return data;
};

