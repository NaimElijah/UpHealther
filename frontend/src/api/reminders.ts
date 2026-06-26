import client from './client';
import type { Reminder, CreateReminderRequest } from '../types';

export const getReminders = async (upgradeId: string): Promise<Reminder[]> => {
  const { data } = await client.get<Reminder[]>(`/api/upgrades/${upgradeId}/reminders`);
  return data;
};

export const createReminder = async (upgradeId: string, req: CreateReminderRequest): Promise<Reminder> => {
  const { data } = await client.post<Reminder>(`/api/upgrades/${upgradeId}/reminders`, req);
  return data;
};

export const updateReminder = async (id: string, req: CreateReminderRequest): Promise<Reminder> => {
  const { data } = await client.put<Reminder>(`/api/reminders/${id}`, req);
  return data;
};

export const deleteReminder = async (id: string): Promise<void> => {
  await client.delete(`/api/reminders/${id}`);
};
