import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { getNotifications, getUnreadCount, markNotificationRead, markAllNotificationsRead } from '../api/notifications';
import { openNotificationSocket } from '../api/notificationSocket';
import { NotificationContext, type ToastData } from './notificationContextValue';
import type { AppNotification } from '../types';
import { toApiError, type ApiError } from '../api/apiError';

/** Query key for the cached notification list, shared by the fetch and every live update below. */
const NOTIF_KEY = ['notifications'];

/**
 * Query key for the server's unread count. It sits under `NOTIF_KEY` on purpose: invalidating that
 * prefix after a failed write refetches the count along with the list.
 */
const UNREAD_KEY = [...NOTIF_KEY, 'unread-count'];

/** The most toasts shown at once; a burst of notifications would otherwise cover the page. */
const MAX_TOASTS = 4;

/** How long a toast stays up before it dismisses itself. */
const TOAST_LIFETIME_MS = 6000;

/**
 * Owns notification state: the list, the unread count, whether the live connection is up, and the
 * toasts.
 *
 * Notifications arrive by two routes and both write to the same query cache — a REST fetch on mount,
 * and the socket of `api/notificationSocket` for anything raised while the tab is open. Live arrivals are
 * de-duplicated by id, so a notification that comes in both ways is shown once.
 *
 * The unread count is the server's, not one counted from the list: the list holds only the fifty most
 * recent, and the count covers every notification (FR-33, #94).
 *
 * It holds the toasts and does not draw them: `ToastContainer` reads them from this context, mounted
 * beside the routes inside this provider and inside the router, because a toast can navigate. So can a
 * desktop notification this provider raises, which is why the provider sits inside the router too. Mounted
 * inside the auth provider because the socket is opened only while signed in. The connection follows
 * the session: it opens when signed in and closes on logout or unmount.
 */
export const NotificationProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  const queryClient = useQueryClient();
  // Held in a ref because the router's navigate changes identity on every move: a dependency of
  // handleIncoming, it would tear the socket down and open another on each one.
  const navigate = useNavigate();
  const navigateRef = useRef(navigate);
  useEffect(() => {
    navigateRef.current = navigate;
  }, [navigate]);
  const [connected, setConnected] = useState(false);
  const [toasts, setToasts] = useState<ToastData[]>([]);
  const [desktopPermission, setDesktopPermission] = useState<NotificationPermission>(
    typeof Notification !== 'undefined' ? Notification.permission : 'denied',
  );
  const [actionError, setActionError] = useState<ApiError | undefined>(undefined);

  const { data: notifications = [], error: listError } = useQuery({
    queryKey: NOTIF_KEY,
    queryFn: getNotifications,
    enabled: isAuthenticated,
  });

  const { data: serverUnread } = useQuery({
    queryKey: UNREAD_KEY,
    queryFn: getUnreadCount,
    enabled: isAuthenticated,
  });

  // Until the server's count is read, or when it cannot be, the unread listed stands in: a lower bound,
  // where zero would hide the badge and every "Mark all read" while unread rows are on screen.
  const unreadCount = serverUnread ?? notifications.filter((n) => !n.read).length;
  const loadError = listError ? toApiError(listError) : undefined;

  /** Removes one toast, whether it was dismissed by the user or timed out. */
  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  /**
   * Shows a notification as a toast: newest first, at most four at once, each auto-dismissed after six
   * seconds. The cap matters — a burst of notifications would otherwise cover the page.
   */
  const pushToast = useCallback(
    (n: AppNotification) => {
      setToasts((prev) =>
        [
          { id: n.id, category: n.category, title: n.title, message: n.message, relatedUpgradeId: n.relatedUpgradeId },
          ...prev,
        ].slice(0, MAX_TOASTS),
      );
      window.setTimeout(() => dismissToast(n.id), TOAST_LIFETIME_MS);
    },
    [dismissToast],
  );

  /**
   * Handles a notification pushed over the socket: writes it into the cache, toasts it, and raises a
   * desktop notification when the tab is in the background.
   *
   * The desktop notification is deliberately conditional on `document.hidden` — the toast already
   * covers the case where the user is looking at the page, and doing both would notify twice.
   */
  const handleIncoming = useCallback(
    (n: AppNotification) => {
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
          // Through the router, as the toast and the dropdown open it: a document navigation would
          // drop the cache and the in-memory token, and spend a refresh from the per-address allowance
          // (NFR-59) to get them back.
          if (n.relatedUpgradeId) navigateRef.current(`/upgrades/${n.relatedUpgradeId}`);
          desktop.close();
        };
      }
    },
    [queryClient, pushToast],
  );

  // The socket follows the session: open while authenticated, closed on logout/unmount. While it is down
  // it reconnects on its own, and nothing is lost meanwhile, since anything missed is still fetched by the
  // REST query.
  // handleIncoming is a dependency, so it is memoised: a new identity each render would tear the
  // socket down and rebuild it on every render.
  useEffect(() => {
    if (!isAuthenticated) return;

    const close = openNotificationSocket({ onNotification: handleIncoming, onConnectionChange: setConnected });

    return () => {
      close();
      setConnected(false);
    };
  }, [isAuthenticated, handleIncoming]);

  /**
   * Cancels a list or count fetch already in flight, before an optimistic write to either. That fetch
   * was answered before the write reached the server, so landing afterwards it would undo the write.
   * `NOTIF_KEY` is a prefix, so this covers the count as well as the list. Cancelling also reverts each
   * query to its state before that fetch; callers await it, as TanStack's optimistic-update guide
   * prescribes, so their write lands after the revert rather than under it.
   */
  const cancelNotificationFetches = useCallback(
    () => queryClient.cancelQueries({ queryKey: NOTIF_KEY }),
    [queryClient],
  );

  /**
   * Marks one notification read, updating the cache first and calling the API after.
   *
   * The optimistic write is what makes the badge respond instantly. The count drops only if the
   * notification was unread, since marking a read one again changes nothing on the server. If the call
   * fails the list and the count are invalidated, so the server's answer replaces the guess rather than
   * the UI keeping a lie, and the failure is kept as `actionError` for the bell and the page to show.
   *
   * The count is also read again once the call succeeds, because the drop is a guess made from the list,
   * and the list can be older than the count: a push refreshes the count alone.
   */
  const markRead = useCallback(
    async (id: string) => {
      // Cleared when a write starts, not when one succeeds: a later success says nothing about an earlier
      // write that was refused, which is unread again.
      setActionError(undefined);
      await cancelNotificationFetches();
      const wasUnread = queryClient.getQueryData<AppNotification[]>(NOTIF_KEY)?.some((n) => n.id === id && !n.read);
      queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) =>
        old.map((n) => (n.id === id ? { ...n, read: true } : n)),
      );
      if (wasUnread) {
        // A count never read is left unread rather than turned into an invented zero.
        queryClient.setQueryData<number>(UNREAD_KEY, (old) => (old === undefined ? undefined : Math.max(0, old - 1)));
      }
      try {
        await markNotificationRead(id);
      } catch (thrown) {
        setActionError(toApiError(thrown));
        await queryClient.invalidateQueries({ queryKey: NOTIF_KEY });
        return;
      }
      await queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
    },
    [queryClient, cancelNotificationFetches],
  );

  /**
   * Marks every notification read, optimistically and with the same rollback-by-invalidation. The count
   * goes to zero, not merely down by the unread ones listed: the server marks every notification read,
   * including those past the fifty fetched. It is read again once the call succeeds, as in `markRead`.
   */
  const markAllRead = useCallback(async () => {
    setActionError(undefined);
    await cancelNotificationFetches();
    queryClient.setQueryData<AppNotification[]>(NOTIF_KEY, (old = []) => old.map((n) => ({ ...n, read: true })));
    queryClient.setQueryData<number>(UNREAD_KEY, 0);
    try {
      await markAllNotificationsRead();
    } catch (thrown) {
      setActionError(toApiError(thrown));
      await queryClient.invalidateQueries({ queryKey: NOTIF_KEY });
      return;
    }
    await queryClient.invalidateQueries({ queryKey: UNREAD_KEY });
  }, [queryClient, cancelNotificationFetches]);

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
      value={{
        notifications,
        unreadCount,
        connected,
        desktopPermission,
        loadError,
        actionError,
        markRead,
        markAllRead,
        requestDesktopPermission,
        toasts,
        dismissToast,
      }}
    >
      {children}
    </NotificationContext.Provider>
  );
};
