import client from './client';
import type { Reminder, CreateReminderRequest } from '../types';

/** Fetches an upgrade's reminders, enabled or not. */
export const getReminders = async (upgradeId: string): Promise<Reminder[]> => {
  const { data } = await client.get<Reminder[]>(`/api/upgrades/${upgradeId}/reminders`);
  return data;
};

/** Adds a reminder to an upgrade; an upgrade may have several. */
export const createReminder = async (upgradeId: string, req: CreateReminderRequest): Promise<Reminder> => {
  const { data } = await client.post<Reminder>(`/api/upgrades/${upgradeId}/reminders`, req);
  return data;
};

/**
 * Reschedules a reminder, addressed by its own id rather than through its upgrade.
 *
 * Omitting `enabled` leaves the current state alone, so rescheduling a disabled reminder does not
 * switch it back on.
 */
export const updateReminder = async (id: string, req: CreateReminderRequest): Promise<Reminder> => {
  const { data } = await client.put<Reminder>(`/api/reminders/${id}`, req);
  return data;
};

/** Deletes a reminder. */
export const deleteReminder = async (id: string): Promise<void> => {
  await client.delete(`/api/reminders/${id}`);
};
