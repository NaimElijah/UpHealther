import { apiPath } from './apiPath';
import client from './client';
import type { ProgressEntry, CreateProgressRequest, StreakSummary } from '../types';

/** Fetches one upgrade's progress history, newest first. */
export const getProgressByUpgrade = async (upgradeId: string): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>(apiPath`/api/upgrades/${upgradeId}/progress`);
  return data;
};

/** Fetches the caller's entries for the last seven days, across all upgrades. */
export const getWeekProgress = async (): Promise<ProgressEntry[]> => {
  const { data } = await client.get<ProgressEntry[]>('/api/progress/week');
  return data;
};

/**
 * Logs a day's progress.
 *
 * `upgradeId` selects the endpoint and is deliberately not sent in the body. A second entry for the
 * same upgrade and date is rejected with a 409 rather than replacing the first.
 */
export const createProgress = async (req: CreateProgressRequest): Promise<ProgressEntry> => {
  const { upgradeId, date, completed, numericValue, unit, rating, note } = req;
  const { data } = await client.post<ProgressEntry>(apiPath`/api/upgrades/${upgradeId}/progress`, {
    date,
    completed,
    numericValue,
    unit,
    rating,
    note,
  } satisfies Omit<CreateProgressRequest, 'upgradeId'>);
  return data;
};

/** Fetches an upgrade's current and longest streaks, both computed server-side. */
export const getStreak = async (upgradeId: string): Promise<StreakSummary> => {
  const { data } = await client.get<StreakSummary>(apiPath`/api/upgrades/${upgradeId}/streak`);
  return data;
};
