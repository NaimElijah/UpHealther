import React from 'react';
import type { AppNotification } from '../../types';
import { categoryMeta, relativeTime } from './notificationMeta';

/**
 * @param notification the notification to render
 * @param onSelect     called with it when pressed; the parent decides what selecting means
 */
interface Props {
  notification: AppNotification;
  onSelect: (notification: AppNotification) => void;
}

/**
 * One row in the notification list: category icon, title, message and relative time.
 *
 * A real button rather than a clickable div, so it is reachable by keyboard and announced as
 * actionable. Unread rows are tinted and carry a dot with an accessible label, since colour alone
 * would not convey the state.
 */
const NotificationItem: React.FC<Props> = ({ notification, onSelect }) => {
  const meta = categoryMeta[notification.category];
  return (
    <button
      onClick={() => onSelect(notification)}
      className={`w-full text-left flex gap-3 p-3 transition-colors hover:bg-sunken ${notification.read ? '' : 'bg-brand-soft/50'}`}
    >
      <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium text-fg truncate">{notification.title}</p>
          {!notification.read && <span className="w-2 h-2 rounded-full bg-brand shrink-0" aria-label="unread" />}
        </div>
        {notification.message && <p className="text-xs text-fg-subtle mt-0.5 line-clamp-2">{notification.message}</p>}
        <p className="text-[11px] text-fg-faint mt-1">{relativeTime(notification.createdAt)}</p>
      </div>
    </button>
  );
};

export default NotificationItem;
