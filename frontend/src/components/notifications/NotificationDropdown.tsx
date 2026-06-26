import React from 'react';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../../hooks/useNotifications';
import NotificationItem from './NotificationItem';
import type { AppNotification } from '../../types';

interface Props {
  onClose: () => void;
}

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
    <div className="absolute right-0 mt-2 w-80 max-w-[calc(100vw-2rem)] bg-white rounded-xl border border-gray-200 shadow-xl z-50 overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
        <span className="font-semibold text-gray-800 text-sm">
          Notifications{unreadCount > 0 && <span className="text-blue-600"> ({unreadCount})</span>}
        </span>
        {unreadCount > 0 && (
          <button onClick={markAllRead} className="text-xs text-blue-600 hover:underline">
            Mark all read
          </button>
        )}
      </div>

      {desktopPermission === 'default' && (
        <button
          onClick={requestDesktopPermission}
          className="w-full text-left px-4 py-2 text-xs text-blue-700 bg-blue-50 hover:bg-blue-100 border-b border-gray-100"
        >
          🔔 Enable desktop notifications
        </button>
      )}

      <div className="max-h-96 overflow-y-auto divide-y divide-gray-100">
        {recent.length === 0 ? (
          <p className="text-sm text-gray-500 text-center py-8">No notifications yet.</p>
        ) : (
          recent.map((n) => <NotificationItem key={n.id} notification={n} onSelect={select} />)
        )}
      </div>

      <button
        onClick={() => { onClose(); navigate('/notifications'); }}
        className="w-full text-center px-4 py-2.5 text-sm font-medium text-blue-600 hover:bg-gray-50 border-t border-gray-100"
      >
        View all notifications
      </button>
    </div>
  );
};

export default NotificationDropdown;
