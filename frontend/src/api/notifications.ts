import client from './client';
import type { AppNotification } from '../types';

/** Fetches the fifty most recent notifications, newest first. Not paginated. */
export const getNotifications = async (): Promise<AppNotification[]> => {
  const { data } = await client.get<AppNotification[]>('/api/notifications');
  return data;
};

/**
 * Fetches the unread count, unwrapping the `{ count }` envelope the API returns.
 *
 * Counts every unread notification, so it can exceed what {@link getNotifications} returns.
 */
export const getUnreadCount = async (): Promise<number> => {
  const { data } = await client.get<{ count: number }>('/api/notifications/unread-count');
  return data.count;
};

/** Marks one notification as read and returns it. */
export const markNotificationRead = async (id: string): Promise<AppNotification> => {
  const { data } = await client.post<AppNotification>(`/api/notifications/${id}/read`);
  return data;
};

/** Marks every unread notification as read in one call. */
export const markAllNotificationsRead = async (): Promise<void> => {
  await client.post('/api/notifications/read-all');
};
