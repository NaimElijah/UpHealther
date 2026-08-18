import { createContext } from 'react';
import type { AppNotification } from '../types';

/**
 * What the notification context exposes: the notification list and unread count, whether the real-time
 * connection is up, and the desktop-notification permission state.
 *
 * `connected` reflects the STOMP socket only. A disconnected client still shows notifications — it
 * fetches them over REST instead — so this is an indicator, not a gate.
 */
export interface NotificationContextType {
  notifications: AppNotification[];
  unreadCount: number;
  connected: boolean;
  desktopPermission: NotificationPermission;
  markRead: (id: string) => void;
  markAllRead: () => void;
  requestDesktopPermission: () => void;
}

/** The context object, split from the provider component for the same Fast Refresh reason as auth. */
export const NotificationContext = createContext<NotificationContextType | null>(null);
