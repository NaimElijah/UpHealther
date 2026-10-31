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
import { useFocusWhenReady } from '../hooks/useFocusWhenReady';
import { findPageHeading } from '../components/ui/pageHeadingFocus';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * What each filter button says. Written in the case it is read in, rather than capitalised by CSS, which
 * a screen reader does not see.
 */
const FILTER_LABELS = { all: 'All', unread: 'Unread' } as const;

/**
 * The full notification list, with an all/unread filter.
 *
 * Reads from the notification context rather than fetching, so it shows the same list the bell does and
 * updates live while open. Selecting one marks it read and follows it to the upgrade it concerns.
 *
 * A list that could not be fetched says so instead of "No notifications yet"; one that could not be
 * refetched keeps the cached list and says so above it, so the list does not read as current. A refused
 * mark-read is shown above the list too, each with the trace id (NFR-30).
 */
const NotificationsPage: React.FC = () => {
  useDocumentTitle('Notifications');
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
  const [filter, setFilter] = useState<'all' | 'unread'>('all');

  const shown = filter === 'unread' ? notifications.filter((n) => !n.read) : notifications;

  const focusWhenReady = useFocusWhenReady();
  /**
   * Runs one of the header's actions, each of which removes its own button once it has acted, and sends
   * focus to the page's heading when it goes rather than letting it fall to `<body>`.
   */
  const actThenRefocus = (action: () => void) => () => {
    const pressed = document.activeElement;
    focusWhenReady(() => (pressed?.isConnected ? undefined : findPageHeading()), pressed);
    action();
  };

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
              <Button variant="secondary" size="sm" onClick={actThenRefocus(requestDesktopPermission)}>
                Enable desktop alerts
              </Button>
            )}
            {unreadCount > 0 && (
              <Button size="sm" onClick={actThenRefocus(markAllRead)}>
                Mark all read
              </Button>
            )}
          </div>
        }
      />

      {/* Toggle buttons that say which is on: the ring beside the fill is the cue that is not colour alone,
          and is far above 3:1 in both themes where the fill against the other is not (WCAG 1.4.1, 1.4.11). */}
      <div role="group" aria-label="Show" className="flex gap-2 mb-4">
        {(['all', 'unread'] as const).map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={filter === f}
            onClick={() => setFilter(f)}
            className={`px-3 py-1.5 rounded-lg border-2 text-sm font-medium transition-colors ${
              filter === f
                ? 'bg-brand text-fg-inverted border-fg'
                : 'bg-muted text-fg-muted border-transparent hover:bg-muted-strong'
            }`}
          >
            {FILTER_LABELS[f]}
            {f === 'unread' && unreadCount > 0 ? ` (${unreadCount})` : ''}
          </button>
        ))}
      </div>

      {actionError && (
        <div className="mb-4">
          <ErrorState inline title="Could not mark your notifications as read." error={actionError} />
        </div>
      )}

      {notifications.length > 0 && loadError && (
        <div className="mb-4">
          <ErrorState
            inline
            title="Your notifications could not be refreshed. What is shown may be out of date."
            error={loadError}
          />
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
