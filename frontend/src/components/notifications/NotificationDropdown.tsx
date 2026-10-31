import React from 'react';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../../hooks/useNotifications';
import NotificationItem from './NotificationItem';
import ErrorState from '../ui/ErrorState';
import type { AppNotification } from '../../types';

/** How many of the most recent notifications the panel lists; the full page lists the rest. */
const PANEL_SIZE = 8;

/**
 * @param id      the panel's id, which the bell's `aria-controls` names
 * @param onClose called after selecting a notification or following View all; it closes the panel and
 *                gives focus back to the bell
 * @param onActed called after an action that keeps the panel open but removes its own button, Mark all
 *                read or the desktop prompt, so focus goes back to the bell rather than to `<body>`
 */
interface Props {
  id: string;
  onClose: () => void;
  onActed: () => void;
}

/**
 * Panel under the bell: the eight most recent notifications, mark-all-read, and a link to the full page.
 *
 * Selecting one marks it read and navigates to the upgrade it concerns; ones with no related upgrade
 * (the daily check-in nudge) just close the panel. The desktop-permission prompt appears only while the
 * browser's answer is still `default`, so a user who has decided either way is not asked again.
 *
 * A list that could not be fetched says so instead of "No notifications yet"; one that could not be
 * refetched keeps the cached rows and says so above them, so they do not read as current. A refused
 * mark-read is shown at the top, each with the trace id (NFR-30). Selecting one closes the panel, so its refusal is
 * seen the next time the panel opens.
 */
const NotificationDropdown: React.FC<Props> = ({ id, onClose, onActed }) => {
  const {
    notifications,
    unreadCount,
    loadError,
    actionError,
    markRead,
    markAllRead,
    desktopPermission,
    requestDesktopPermission,
  } = useNotifications();
  const navigate = useNavigate();
  const recent = notifications.slice(0, PANEL_SIZE);

  const select = (n: AppNotification) => {
    if (!n.read) markRead(n.id);
    onClose();
    if (n.relatedUpgradeId) navigate(`/upgrades/${n.relatedUpgradeId}`);
  };

  // Below `sm` the panel is placed against the viewport, a gutter in from each side and just under the bar.
  // Anchored to the bell, which sits left of Logout, its right edge stopped short of the window's and its
  // left edge ran off it: at 320px it spanned -61px to 227px, and the part off screen could not be
  // scrolled to (WCAG 1.4.10). Fixed, it cannot be scrolled with the page either, so it scrolls itself on a
  // short window. Measured in a browser.
  return (
    <div
      id={id}
      className="fixed inset-x-4 top-[4.5rem] max-h-[calc(100vh-5.5rem)] overflow-y-auto sm:absolute sm:inset-x-auto sm:top-full sm:right-0 sm:mt-2 sm:w-80 sm:max-w-[calc(100vw-2rem)] sm:max-h-none sm:overflow-hidden bg-surface rounded-xl border border-line shadow-xl z-50"
    >
      <div className="flex items-center justify-between px-4 py-3 border-b border-line-subtle">
        <span className="font-semibold text-fg-muted text-sm">
          Notifications{unreadCount > 0 && <span className="text-brand-fg"> ({unreadCount})</span>}
        </span>
        {unreadCount > 0 && (
          <button
            type="button"
            onClick={() => {
              markAllRead();
              onActed();
            }}
            className="text-xs text-brand-fg hover:underline"
          >
            Mark all read
          </button>
        )}
      </div>

      {desktopPermission === 'default' && (
        <button
          type="button"
          onClick={() => {
            requestDesktopPermission();
            onActed();
          }}
          className="w-full text-left px-4 py-2 text-xs text-brand-fg bg-brand-soft hover:bg-brand-soft-hover border-b border-line-subtle"
        >
          <span aria-hidden="true">🔔</span> Enable desktop notifications
        </button>
      )}

      {actionError && (
        <div className="px-4 py-2 border-b border-line-subtle">
          <ErrorState inline title="Could not mark your notifications as read." error={actionError} />
        </div>
      )}

      {recent.length > 0 && loadError && (
        <div className="px-4 py-2 border-b border-line-subtle">
          <ErrorState
            inline
            title="Your notifications could not be refreshed. What is shown may be out of date."
            error={loadError}
          />
        </div>
      )}

      <div className="max-h-96 overflow-y-auto divide-y divide-line-subtle">
        {recent.length === 0 && loadError && (
          <div className="p-4">
            <ErrorState inline title="Could not load your notifications." error={loadError} />
          </div>
        )}
        {recent.length === 0 && !loadError && (
          <p className="text-sm text-fg-subtle text-center py-8">No notifications yet.</p>
        )}
        {recent.map((n) => (
          <NotificationItem key={n.id} notification={n} onSelect={select} />
        ))}
      </div>

      <button
        type="button"
        onClick={() => {
          onClose();
          navigate('/notifications');
        }}
        className="w-full text-center px-4 py-2.5 text-sm font-medium text-brand-fg hover:bg-sunken border-t border-line-subtle"
      >
        View all notifications
      </button>
    </div>
  );
};

export default NotificationDropdown;
