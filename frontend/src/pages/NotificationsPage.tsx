import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../hooks/useNotifications';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import EmptyState from '../components/ui/EmptyState';
import NotificationItem from '../components/notifications/NotificationItem';
import type { AppNotification } from '../types';

/**
 * The full notification list, with an all/unread filter.
 *
 * Reads from the notification context rather than fetching, so it shows the same list the bell does and
 * updates live while open. Selecting one marks it read and follows it to the upgrade it concerns.
 */
const NotificationsPage: React.FC = () => {
  const { notifications, unreadCount, markRead, markAllRead, desktopPermission, requestDesktopPermission } =
    useNotifications();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<'all' | 'unread'>('all');

  const shown = filter === 'unread' ? notifications.filter((n) => !n.read) : notifications;

  const select = (n: AppNotification) => {
    if (!n.read) markRead(n.id);
    if (n.relatedUpgradeId) navigate(`/upgrades/${n.relatedUpgradeId}`);
  };

  return (
    <div className="max-w-3xl mx-auto">
      <PageHeader
        title="Notifications"
        subtitle={unreadCount > 0 ? `${unreadCount} unread` : 'You are all caught up'}
        action={
          <div className="flex gap-2">
            {desktopPermission === 'default' && (
              <Button variant="secondary" size="sm" onClick={requestDesktopPermission}>Enable desktop alerts</Button>
            )}
            {unreadCount > 0 && <Button size="sm" onClick={markAllRead}>Mark all read</Button>}
          </div>
        }
      />

      <div className="flex gap-2 mb-4">
        {(['all', 'unread'] as const).map((f) => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium capitalize transition-colors ${
              filter === f ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
          >
            {f}{f === 'unread' && unreadCount > 0 ? ` (${unreadCount})` : ''}
          </button>
        ))}
      </div>

      {shown.length === 0 ? (
        <EmptyState
          icon="🔔"
          title={filter === 'unread' ? 'No unread notifications' : 'No notifications yet'}
          description="Updates about your upgrades, streaks, reminders and overdue items will show up here."
        />
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden divide-y divide-gray-100">
          {shown.map((n) => (
            <NotificationItem key={n.id} notification={n} onSelect={select} />
          ))}
        </div>
      )}
    </div>
  );
};

export default NotificationsPage;
