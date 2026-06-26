import { createContext } from 'react';
import type { AppNotification } from '../types';

export interface NotificationContextType {
  notifications: AppNotification[];
  unreadCount: number;
  connected: boolean;
  desktopPermission: NotificationPermission;
  markRead: (id: string) => void;
  markAllRead: () => void;
  requestDesktopPermission: () => void;
}

export const NotificationContext = createContext<NotificationContextType | null>(null);
