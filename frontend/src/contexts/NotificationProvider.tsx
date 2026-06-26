import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../hooks/useAuth';
import { getNotifications, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import { NotificationContext } from './notificationContextValue';
import ToastContainer, { type ToastData } from '../components/notifications/ToastContainer';
import type { AppNotification } from '../types';

const NOTIF_KEY = ['notifications'];

/** Build the WebSocket URL. Same-origin /ws (via the Vite/nginx proxy) by default; derived from
 * VITE_API_URL when the API is on another origin. */
function buildWsUrl(): string {
  const apiUrl = import.meta.env.VITE_API_URL;
  if (apiUrl && /^https?:\/\//.test(apiUrl)) {
    return apiUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws`;
}

export const NotificationProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { token, isAuthenticated } = useAuth();
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);
  const [toasts, setToasts] = useState<ToastData[]>([]);
  const [desktopPermission, setDesktopPermission] = useState<NotificationPermission>(
    typeof Notification !== 'undefined' ? Notification.permission : 'denied',
  );
  const clientRef = useRef<Client | null>(null);

  const { data: notifications = [] } = useQuery({
    queryKey: NOTIF_KEY,
    queryFn: getNotifications,
    enabled: isAuthenticated,
  });

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const pushToast = useCallback((n: AppNotification) => {
    setToasts((prev) =>
      [{ id: n.id, category: n.category, title: n.title, message: n.message, relatedUpgradeId: n.relatedUpgradeId }, ...prev].slice(0, 4),
    );
    window.setTimeout(() => dismissToast(n.id), 6000);
  }, [dismissToast]);

  const handleIncoming = useCallback((n: AppNotification) => {
    // Prepend to the cached list (de-duped) so the bell/badge/list update live.
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) =>
      old.some((x) => x.id === n.id) ? old : [n, ...old],
    );
    pushToast(n);
    // OS/desktop notification only when the tab is backgrounded and permission was granted.
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted' && document.hidden) {
      const desktop = new Notification(n.title, { body: n.message ?? '' });
      desktop.onclick = () => {
        window.focus();
        if (n.relatedUpgradeId) window.location.assign(`/upgrades/${n.relatedUpgradeId}`);
        desktop.close();
      };
    }
  }, [queryClient, pushToast]);

  // STOMP connection lifecycle — connect while authenticated, disconnect on logout/unmount.
  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const client = new Client({
      brokerURL: buildWsUrl(),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/user/queue/notifications', (message) => {
          try {
            handleIncoming(JSON.parse(message.body) as AppNotification);
          } catch {
            /* ignore malformed frames */
          }
        });
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [isAuthenticated, token, handleIncoming]);

  const markRead = useCallback((id: string) => {
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) =>
      old.map((n) => (n.id === id ? { ...n, read: true } : n)),
    );
    markNotificationRead(id).catch(() => queryClient.invalidateQueries({ queryKey: NOTIF_KEY }));
  }, [queryClient]);

  const markAllRead = useCallback(() => {
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) => old.map((n) => ({ ...n, read: true })));
    markAllNotificationsRead().catch(() => queryClient.invalidateQueries({ queryKey: NOTIF_KEY }));
  }, [queryClient]);

  const requestDesktopPermission = useCallback(() => {
    if (typeof Notification === 'undefined') return;
    Notification.requestPermission().then(setDesktopPermission);
  }, []);

  const unreadCount = notifications.filter((n) => !n.read).length;

  return (
    <NotificationContext.Provider
      value={{ notifications, unreadCount, connected, desktopPermission, markRead, markAllRead, requestDesktopPermission }}
    >
      {children}
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
    </NotificationContext.Provider>
  );
};
