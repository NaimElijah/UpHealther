import client from './client';
import type { HealthUpgrade, CreateUpgradeRequest, UpgradeStatus } from '../types';

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

/**
 * Maps a target UpgradeStatus to the corresponding action endpoint.
 * IDEA is the initial state set at creation and has no action endpoint.
 * Rescheduling ABANDONED upgrades uses rescheduleUpgrade() separately.
 */
export const performUpgradeAction = async (id: string, status: UpgradeStatus): Promise<HealthUpgrade> => {
  const actionMap: Partial<Record<UpgradeStatus, string>> = {
    PLANNED: 'plan',
    ACTIVE: 'activate',
    PAUSED: 'pause',
    COMPLETED: 'complete',
    ABANDONED: 'abandon',
    // IDEA has no action endpoint — it is the initial state set at creation
  };
  const action = actionMap[status];
  if (!action) throw new Error(`Unsupported status transition to ${status}`);
  const { data } = await client.post<HealthUpgrade>(`/api/upgrades/${id}/${action}`);
  return data;
};

export const rescheduleUpgrade = async (id: string, newDate: string): Promise<HealthUpgrade> => {
  const { data } = await client.post<HealthUpgrade>(`/api/upgrades/${id}/reschedule`, { newDate });
  return data;
};

export const deleteUpgrade = async (id: string): Promise<void> => {
  await client.delete(`/api/upgrades/${id}`);
};

