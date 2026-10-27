import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import NotificationBell from './NotificationBell';
import { NotificationContext, type NotificationContextType } from '../../contexts/notificationContextValue';

function renderBell(unreadCount: number) {
  const notifications: NotificationContextType = {
    notifications: [],
    unreadCount,
    connected: true,
    desktopPermission: 'default',
    markRead: () => {},
    markAllRead: () => {},
    requestDesktopPermission: () => {},
  };
  return render(
    <NotificationContext.Provider value={notifications}>
      <NotificationBell />
    </NotificationContext.Provider>,
  );
}

/**
 * The bell's unread badge, and the count in its accessible name.
 *
 * The badge is capped at "9+" so it cannot grow wide enough to disturb the navbar; the name carries the
 * whole count, because a screen reader announces the name and not the badge, and the cap is a visual
 * concern only.
 */
describe('NotificationBell', () => {
  it('GivenNineUnread_WhenTheBellRenders_ThenTheBadgeShowsNineAndTheNameSaysSo', () => {
    renderBell(9);

    const bell = screen.getByRole('button', { name: 'Notifications (9 unread)' });
    expect(within(bell).getByText('9')).toBeDefined();
  });

  it('GivenTenUnread_WhenTheBellRenders_ThenTheBadgeIsCappedButTheNameGivesTheWholeCount', () => {
    renderBell(10);

    const bell = screen.getByRole('button', { name: 'Notifications (10 unread)' });
    expect(within(bell).getByText('9+')).toBeDefined();
    expect(within(bell).queryByText('10')).toBeNull();
  });

  it('GivenNothingUnread_WhenTheBellRenders_ThenThereIsNoBadgeAndTheNameIsPlain', () => {
    renderBell(0);

    const bell = screen.getByRole('button', { name: 'Notifications' });
    expect(within(bell).queryByText('0')).toBeNull();
  });
});
