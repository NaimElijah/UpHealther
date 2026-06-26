import client from './client';
import type { AppNotification } from '../types';

export const getNotifications = async (): Promise<AppNotification[]> => {
  const { data } = await client.get<AppNotification[]>('/api/notifications');
  return data;
};

export const getUnreadCount = async (): Promise<number> => {
  const { data } = await client.get<{ count: number }>('/api/notifications/unread-count');
  return data.count;
};

export const markNotificationRead = async (id: string): Promise<AppNotification> => {
  const { data } = await client.post<AppNotification>(`/api/notifications/${id}/read`);
  return data;
};

export const markAllNotificationsRead = async (): Promise<void> => {
  await client.post('/api/notifications/read-all');
};
