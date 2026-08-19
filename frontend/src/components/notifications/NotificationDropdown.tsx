import React from 'react';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../../hooks/useNotifications';
import NotificationItem from './NotificationItem';
import type { AppNotification } from '../../types';

/** @param onClose called after selecting a notification, and by the bell's outside-click handler */
interface Props {
  onClose: () => void;
}

/**
 * Panel under the bell: the eight most recent notifications, mark-all-read, and a link to the full page.
 *
 * Selecting one marks it read and navigates to the upgrade it concerns; ones with no related upgrade
 * (the daily check-in nudge) just close the panel. The desktop-permission prompt appears only while the
 * browser's answer is still `default`, so a user who has decided either way is not asked again.
 */
const NotificationDropdown: React.FC<Props> = ({ onClose }) => {
  const { notifications, unreadCount, markRead, markAllRead, desktopPermission, requestDesktopPermission } =
    useNotifications();
  const navigate = useNavigate();
  const recent = notifications.slice(0, 8);

  const select = (n: AppNotification) => {
    if (!n.read) markRead(n.id);
    onClose();
    if (n.relatedUpgradeId) navigate(`/upgrades/${n.relatedUpgradeId}`);
  };

  return (
    <div className="absolute right-0 mt-2 w-80 max-w-[calc(100vw-2rem)] bg-surface rounded-xl border border-line shadow-xl z-50 overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3 border-b border-line-subtle">
        <span className="font-semibold text-fg-muted text-sm">
          Notifications{unreadCount > 0 && <span className="text-brand-fg"> ({unreadCount})</span>}
        </span>
        {unreadCount > 0 && (
          <button onClick={markAllRead} className="text-xs text-brand-fg hover:underline">
            Mark all read
          </button>
        )}
      </div>

      {desktopPermission === 'default' && (
        <button
          onClick={requestDesktopPermission}
          className="w-full text-left px-4 py-2 text-xs text-brand-fg bg-brand-soft hover:bg-brand-soft-hover border-b border-line-subtle"
        >
          🔔 Enable desktop notifications
        </button>
      )}

      <div className="max-h-96 overflow-y-auto divide-y divide-line-subtle">
        {recent.length === 0 ? (
          <p className="text-sm text-fg-subtle text-center py-8">No notifications yet.</p>
        ) : (
          recent.map((n) => <NotificationItem key={n.id} notification={n} onSelect={select} />)
        )}
      </div>

      <button
        onClick={() => { onClose(); navigate('/notifications'); }}
        className="w-full text-center px-4 py-2.5 text-sm font-medium text-brand-fg hover:bg-sunken border-t border-line-subtle"
      >
        View all notifications
      </button>
    </div>
  );
};

export default NotificationDropdown;
