import client from './client';
import type { HealthUpgrade, CreateUpgradeRequest, UpgradeStatus } from '../types';

/** Lists the caller's upgrades, optionally narrowed to one status. */
export const getUpgrades = async (status?: UpgradeStatus): Promise<HealthUpgrade[]> => {
  const params = status ? { status } : {};
  const { data } = await client.get<HealthUpgrade[]>('/api/upgrades', { params });
  return data;
};

/** Fetches one upgrade, including its tracking configuration. */
export const getUpgradeById = async (id: string): Promise<HealthUpgrade> => {
  const { data } = await client.get<HealthUpgrade>(`/api/upgrades/${id}`);
  return data;
};

/** Creates an upgrade. It always starts as an `IDEA`, whatever else is sent. */
export const createUpgrade = async (req: CreateUpgradeRequest): Promise<HealthUpgrade> => {
  const { data } = await client.post<HealthUpgrade>('/api/upgrades', req);
  return data;
};

/**
 * Replaces an upgrade's editable fields.
 *
 * A full replacement server-side despite the `Partial` type here: a field left out is stored as null,
 * not left at its previous value.
 */
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

/**
 * Moves an upgrade's planned start date. Also the only way back from `ABANDONED`, which this returns
 * to `PLANNED`.
 */
export const rescheduleUpgrade = async (id: string, newDate: string): Promise<HealthUpgrade> => {
  const { data } = await client.post<HealthUpgrade>(`/api/upgrades/${id}/reschedule`, { newDate });
  return data;
};

/** Deletes an upgrade permanently. Its progress entries and reflections are not cascaded. */
export const deleteUpgrade = async (id: string): Promise<void> => {
  await client.delete(`/api/upgrades/${id}`);
};

