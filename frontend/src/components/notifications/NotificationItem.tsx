import React from 'react';
import type { AppNotification } from '../../types';
import { categoryStyle, relativeTime } from './notificationMeta';

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
 * actionable. Unread rows are tinted and carry a dot, and say "Unread:" in visually hidden text first, so
 * the state is in the row's name and not in its colour alone. The dot is decoration beside that text.
 * It used to be the dot that carried an `aria-label`, which ARIA 1.2 does not allow on an empty generic
 * element (WCAG 4.1.2, 1.4.1).
 */
const NotificationItem: React.FC<Props> = ({ notification, onSelect }) => {
  const meta = categoryStyle(notification.category);
  return (
    <button
      onClick={() => onSelect(notification)}
      className={`w-full text-left flex gap-3 p-3 transition-colors hover:bg-sunken ${notification.read ? '' : 'bg-brand-soft/50'}`}
    >
      {!notification.read && <span className="sr-only">Unread:</span>}
      <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium text-fg truncate">{notification.title}</p>
          {!notification.read && <span className="w-2 h-2 rounded-full bg-brand shrink-0" aria-hidden="true" />}
        </div>
        {notification.message && <p className="text-xs text-fg-subtle mt-0.5 line-clamp-2">{notification.message}</p>}
        <p className="text-[11px] text-fg-faint mt-1">{relativeTime(notification.createdAt)}</p>
      </div>
    </button>
  );
};

export default NotificationItem;
