import React from 'react';
import type { AppNotification } from '../../types';
import { categoryMeta, relativeTime } from './notificationMeta';

interface Props {
  notification: AppNotification;
  onSelect: (notification: AppNotification) => void;
}

const NotificationItem: React.FC<Props> = ({ notification, onSelect }) => {
  const meta = categoryMeta[notification.category];
  return (
    <button
      onClick={() => onSelect(notification)}
      className={`w-full text-left flex gap-3 p-3 transition-colors hover:bg-gray-50 ${notification.read ? '' : 'bg-blue-50/50'}`}
    >
      <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium text-gray-900 truncate">{notification.title}</p>
          {!notification.read && <span className="w-2 h-2 rounded-full bg-blue-500 shrink-0" aria-label="unread" />}
        </div>
        {notification.message && <p className="text-xs text-gray-500 mt-0.5 line-clamp-2">{notification.message}</p>}
        <p className="text-[11px] text-gray-400 mt-1">{relativeTime(notification.createdAt)}</p>
      </div>
    </button>
  );
};

export default NotificationItem;
