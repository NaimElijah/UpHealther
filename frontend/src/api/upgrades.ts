import client from './client';
import type { ActionTarget, HealthUpgrade, CreateUpgradeRequest, UpgradeStatus } from '../types';

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
 * Moves an upgrade to `status` through that status's action endpoint, which takes no body.
 *
 * The parameter type is what keeps `PLANNED` out: both ways into it carry a date, so planning goes
 * through planUpgrade() and rescheduling through rescheduleUpgrade() (#88).
 */
export const performUpgradeAction = async (id: string, status: ActionTarget): Promise<HealthUpgrade> => {
  const actionMap: Record<ActionTarget, string> = {
    ACTIVE: 'activate',
    PAUSED: 'pause',
    COMPLETED: 'complete',
    ABANDONED: 'abandon',
  };
  const { data } = await client.post<HealthUpgrade>(`/api/upgrades/${id}/${actionMap[status]}`);
  return data;
};

/**
 * Moves an `IDEA` to `PLANNED`, the only way out of `IDEA`.
 *
 * @param plannedStartDate `YYYY-MM-DD`, required by the API and free to be in the past, since users plan
 *                         retroactively
 * @throws the API's 422 when the upgrade is no longer an idea, and 404 when it is not the caller's
 */
export const planUpgrade = async (id: string, plannedStartDate: string): Promise<HealthUpgrade> => {
  const { data } = await client.post<HealthUpgrade>(`/api/upgrades/${id}/plan`, { plannedStartDate });
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

