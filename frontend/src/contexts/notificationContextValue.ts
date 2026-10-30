import { createContext } from 'react';
import type { AppNotification } from '../types';
import type { ApiError } from '../api/apiError';

/**
 * The subset of a notification a toast needs.
 *
 * Derived from `AppNotification` with `Pick`, so a field renamed there fails to compile here instead of
 * silently rendering nothing.
 */
export interface ToastData extends Pick<AppNotification, 'category' | 'title' | 'message' | 'relatedUpgradeId'> {
  id: string;
}

/**
 * What the notification context exposes: the notification list and unread count, whether the real-time
 * connection is up, the desktop-notification permission state, and the toasts.
 *
 * `toasts` are the live arrivals on screen, newest first and capped by the provider; `dismissToast`
 * removes one, and is what the close button, a selection and the auto-dismiss timer all call.
 *
 * `connected` reflects the STOMP socket only. A disconnected client still shows notifications — it
 * fetches them over REST instead — so this is an indicator, not a gate.
 *
 * `loadError` is why the list could not be fetched; an empty list is then not "no notifications".
 * `actionError` is why the last mark-read or mark-all-read was refused, and the next one clears it as it
 * starts. Both carry the trace id (NFR-30).
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
  toasts: ToastData[];
  dismissToast: (id: string) => void;
}

/** The context object, split from the provider component for the same Fast Refresh reason as auth. */
export const NotificationContext = createContext<NotificationContextType | null>(null);
