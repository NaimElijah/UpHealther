import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '../hooks/useNotifications';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import EmptyState from '../components/ui/EmptyState';
import NotificationItem from '../components/notifications/NotificationItem';
import type { AppNotification } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';

/**
 * The full notification list, with an all/unread filter.
 *
 * Reads from the notification context rather than fetching, so it shows the same list the bell does and
 * updates live while open. Selecting one marks it read and follows it to the upgrade it concerns.
 *
 * A list that could not be fetched says so instead of "No notifications yet", and a refused mark-read
 * is shown above the list, both with the trace id (NFR-30).
 */
const NotificationsPage: React.FC = () => {
  const {
    notifications, unreadCount, loadError, actionError, markRead, markAllRead, desktopPermission, requestDesktopPermission,
  } = useNotifications();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<'all' | 'unread'>('all');

  const shown = filter === 'unread' ? notifications.filter((n) => !n.read) : notifications;

  const select = (n: AppNotification) => {
    if (!n.read) markRead(n.id);
    if (n.relatedUpgradeId) navigate(`/upgrades/${n.relatedUpgradeId}`);
  };

  return (
    <PageContainer>
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
              filter === f ? 'bg-brand text-fg-inverted' : 'bg-muted text-fg-muted hover:bg-muted-strong'
            }`}
          >
            {f}{f === 'unread' && unreadCount > 0 ? ` (${unreadCount})` : ''}
          </button>
        ))}
      </div>

      {actionError && (
        <div className="mb-4">
          <ErrorState inline title="Could not mark your notifications as read." error={actionError} />
        </div>
      )}

      {notifications.length === 0 && loadError ? (
        <ErrorState title="Could not load your notifications." error={loadError} />
      ) : shown.length === 0 ? (
        <EmptyState
          icon="🔔"
          title={filter === 'unread' ? 'No unread notifications' : 'No notifications yet'}
          description="Updates about your upgrades, streaks, reminders and overdue items will show up here."
        />
      ) : (
        <div className="bg-surface rounded-xl border border-line shadow-sm overflow-hidden divide-y divide-line-subtle">
          {shown.map((n) => (
            <NotificationItem key={n.id} notification={n} onSelect={select} />
          ))}
        </div>
      )}
    </PageContainer>
  );
};

export default NotificationsPage;
