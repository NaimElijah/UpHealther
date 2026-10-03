import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../hooks/useAuth';
import { getAccessToken, hasUsableAccessToken } from '../api/tokenStore';
import { renewSession } from '../api/client';
import { getNotifications, getUnreadCount, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import { NotificationContext } from './notificationContextValue';
import ToastContainer, { type ToastData } from '../components/notifications/ToastContainer';
import type { AppNotification } from '../types';

/** Query key for the cached notification list, shared by the fetch and every live update below. */
const NOTIF_KEY = ['notifications'];

/**
 * Query key for the server's unread count. It sits under `NOTIF_KEY` on purpose: invalidating that
 * prefix after a failed write refetches the count along with the list.
 */
const UNREAD_KEY = [...NOTIF_KEY, 'unread-count'];

/**
 * Builds the WebSocket URL.
 *
 * Same-origin `/ws` through the Vite or nginx proxy by default, so the socket needs no CORS setup;
 * derived from `VITE_API_URL` only when the API is on another origin. The scheme is upgraded in step
 * with the page's, since a `ws://` socket on an `https://` page is blocked by the browser.
 */
function buildWsUrl(): string {
  const apiUrl = import.meta.env.VITE_API_URL;
  if (apiUrl && /^https?:\/\//.test(apiUrl)) {
    return apiUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws`;
}

/**
 * Owns notification state: the list, the unread count, the live connection and the toasts.
 *
 * Notifications arrive by two routes and both write to the same query cache — a REST fetch on mount,
 * and a STOMP subscription for anything raised while the tab is open. Live arrivals are de-duplicated
 * by id, so a notification that comes in both ways is shown once.
 *
 * The unread count is the server's, not one counted from the list: the list holds only the fifty most
 * recent, and the count covers every notification (FR-33, #94).
 *
 * Mounted inside the router because a toast can navigate, and inside the auth provider because the
 * socket authenticates with an access token. The connection follows the session: it opens when signed
 * in and closes on logout or unmount.
 */
export const NotificationProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
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

  const { data: unreadCount = 0 } = useQuery({
    queryKey: UNREAD_KEY,
    queryFn: getUnreadCount,
    enabled: isAuthenticated,
  });

  /** Removes one toast, whether it was dismissed by the user or timed out. */
  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  /**
   * Shows a notification as a toast: newest first, at most four at once, each auto-dismissed after six
   * seconds. The cap matters — a burst of notifications would otherwise cover the page.
   */
  const pushToast = useCallback((n: AppNotification) => {
    setToasts((prev) =>
      [{ id: n.id, category: n.category, title: n.title, message: n.message, relatedUpgradeId: n.relatedUpgradeId }, ...prev].slice(0, 4),
    );
    window.setTimeout(() => dismissToast(n.id), 6000);
  }, [dismissToast]);

  /**
   * Handles a notification pushed over the socket: writes it into the cache, toasts it, and raises a
   * desktop notification when the tab is in the background.
   *
   * The desktop notification is deliberately conditional on `document.hidden` — the toast already
   * covers the case where the user is looking at the page, and doing both would notify twice.
   */
  const handleIncoming = useCallback((n: AppNotification) => {
    // Prepend to the cached list (de-duped) so the bell/badge/list update live.
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) =>
      old.some((x) => x.id === n.id) ? old : [n, ...old],
    );
    // Read again rather than incremented: the count already fetched may include this notification.
    queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
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
  // Reconnects every five seconds while the socket is down; nothing is lost meanwhile, since anything
  // missed is still fetched by the REST query.
  // handleIncoming is a dependency, so it is memoised: a new identity each render would tear the
  // socket down and rebuild it on every render.
  useEffect(() => {
    if (!isAuthenticated) return;

    const client = new Client({
      brokerURL: buildWsUrl(),
      // Read at connect time rather than captured when the effect ran. The access token is short
      // lived and renewed underneath us, so a captured one would be stale by the first reconnect -
      // and putting it in the dependency list instead would tear the socket down and rebuild it
      // every fifteen minutes, losing the subscription each time for no reason.
      //
      // It is also renewed here when it has lapsed. Reading it without checking was an infinite
      // loop waiting to happen: a tab left idle past the token's fifteen minutes with no HTTP
      // traffic to renew it would reconnect with a dead token, be refused, and retry the same dead
      // token every five seconds - live notifications silently gone, and a refused CONNECT in the
      // server log every five seconds forever.
      beforeConnect: async () => {
        try {
          if (!hasUsableAccessToken()) {
            await renewSession();
          }
          const current = getAccessToken();
          client.connectHeaders = current ? { Authorization: `Bearer ${current}` } : {};
        } catch {
          // stompjs 7 awaits this hook and schedules no reconnect if it throws, so a failure here
          // would end the socket permanently rather than for one attempt. A connect with no header
          // is refused by the server, which is a reconnect rather than a dead client.
        }
      },
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/user/queue/notifications', (message) => {
          try {
            handleIncoming(JSON.parse(message.body) as AppNotification);
          } catch {
            // A frame that will not parse is dropped rather than thrown: an exception here would
            // propagate into the STOMP client and tear down the subscription, costing every later
            // notification as well as this one.
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
  }, [isAuthenticated, handleIncoming]);

  /**
   * Cancels a count fetch already in flight, before an optimistic write to the count. That fetch was
   * answered before the write reached the server, so landing afterwards it would undo the write.
   * Cancelling also reverts the query to its state before that fetch; callers await it, as TanStack's
   * optimistic-update guide prescribes, so their write lands after the revert rather than under it.
   */
  const cancelCountFetch = useCallback(
    () => queryClient.cancelQueries({ queryKey: UNREAD_KEY }),
    [queryClient],
  );

  /**
   * Marks one notification read, updating the cache first and calling the API after.
   *
   * The optimistic write is what makes the badge respond instantly. The count drops only if the
   * notification was unread, since marking a read one again changes nothing on the server. If the call
   * fails the list and the count are invalidated, so the server's answer replaces the guess rather than
   * the UI keeping a lie.
   *
   * The count is also read again once the call succeeds, because the drop is a guess made from the list,
   * and the list can be older than the count: a push refreshes the count alone.
   */
  const markRead = useCallback(async (id: string) => {
    const wasUnread = queryClient.getQueryData<AppNotification[]>(NOTIF_KEY)?.some((n) => n.id === id && !n.read);
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) =>
      old.map((n) => (n.id === id ? { ...n, read: true } : n)),
    );
    if (wasUnread) {
      await cancelCountFetch();
      queryClient.setQueryData<number>(UNREAD_KEY, (old = 0) => Math.max(0, old - 1));
    }
    try {
      await markNotificationRead(id);
    } catch {
      await queryClient.invalidateQueries({ queryKey: NOTIF_KEY });
      return;
    }
    await queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
  }, [queryClient, cancelCountFetch]);

  /**
   * Marks every notification read, optimistically and with the same rollback-by-invalidation. The count
   * goes to zero, not merely down by the unread ones listed: the server marks every notification read,
   * including those past the fifty fetched. It is read again once the call succeeds, as in `markRead`.
   */
  const markAllRead = useCallback(async () => {
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) => old.map((n) => ({ ...n, read: true })));
    await cancelCountFetch();
    queryClient.setQueryData<number>(UNREAD_KEY, 0);
    try {
      await markAllNotificationsRead();
    } catch {
      await queryClient.invalidateQueries({ queryKey: NOTIF_KEY });
      return;
    }
    await queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
  }, [queryClient, cancelCountFetch]);

  /**
   * Asks the browser for desktop-notification permission.
   *
   * Guarded because the API is absent in insecure contexts and some embedded browsers, where reading
   * `Notification` at all would throw.
   */
  const requestDesktopPermission = useCallback(() => {
    if (typeof Notification === 'undefined') return;
    Notification.requestPermission().then(setDesktopPermission);
  }, []);

  return (
    <NotificationContext.Provider
      value={{ notifications, unreadCount, connected, desktopPermission, markRead, markAllRead, requestDesktopPermission }}
    >
      {children}
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
    </NotificationContext.Provider>
  );
};
