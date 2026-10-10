import { createContext } from 'react';
import type { AppNotification } from '../types';
import type { ApiError } from '../api/apiError';

/**
 * What the notification context exposes: the notification list and unread count, whether the real-time
 * connection is up, and the desktop-notification permission state.
 *
 * `connected` reflects the STOMP socket only. A disconnected client still shows notifications — it
 * fetches them over REST instead — so this is an indicator, not a gate.
 *
 * `loadError` is why the list could not be fetched; an empty list is then not "no notifications".
 * `actionError` is why the last mark-read or mark-all-read was refused, and the next one that succeeds
 * clears it. Both carry the trace id (NFR-30).
 */
export interface NotificationContextType {
  notifications: AppNotification[];
  unreadCount: number;
  connected: boolean;
  desktopPermission: NotificationPermission;
  loadError?: ApiError;
  actionError?: ApiError;
  markRead: (id: string) => void;
  markAllRead: () => void;
  requestDesktopPermission: () => void;
}

/** The context object, split from the provider component for the same Fast Refresh reason as auth. */
export const NotificationContext = createContext<NotificationContextType | null>(null);
