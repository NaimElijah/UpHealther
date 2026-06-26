import { useContext } from 'react';
import { NotificationContext } from '../contexts/notificationContextValue';

export const useNotifications = () => {
  const ctx = useContext(NotificationContext);
  if (!ctx) throw new Error('useNotifications must be used within NotificationProvider');
  return ctx;
};
