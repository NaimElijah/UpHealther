import { useContext } from 'react';
import { NotificationContext } from '../contexts/notificationContextValue';

/**
 * Reads the notification context: the current list, the unread count, and the actions that mutate them.
 *
 * @throws Error when called outside `NotificationProvider`
 */
export const useNotifications = () => {
  const ctx = useContext(NotificationContext);
  if (!ctx) throw new Error('useNotifications must be used within NotificationProvider');
  return ctx;
};
