import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NotificationsPage from './NotificationsPage';
import { NotificationContext, type NotificationContextType } from '../contexts/notificationContextValue';

/** The context the page reads, with nothing in it unless a test says otherwise. */
function aContext(overrides: Partial<NotificationContextType> = {}): NotificationContextType {
  return {
    notifications: [],
    unreadCount: 0,
    connected: true,
    desktopPermission: 'granted',
    markRead: vi.fn(),
    markAllRead: vi.fn(),
    requestDesktopPermission: vi.fn(),
    ...overrides,
  };
}

function renderPage(context: NotificationContextType) {
  return render(
    <NotificationContext.Provider value={context}>
      <MemoryRouter>
        <NotificationsPage />
      </MemoryRouter>
    </NotificationContext.Provider>,
  );
}

/**
 * NFR-30 (#96) — the notifications page says when its list could not be fetched, or a mark-read was
 * refused, with the trace id that finds it. Before, a failed fetch read "No notifications yet".
 */
describe('NotificationsPage', () => {
  it('GivenTheListCouldNotBeFetched_WhenThePageOpens_ThenItSaysSoWithTheReferenceRatherThanNoNotifications', () => {
    renderPage(aContext({ loadError: { message: 'Internal server error', traceId: 'trace-121' } }));

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Could not load your notifications.');
    expect(alert.textContent).toContain('trace-121');
    expect(screen.queryByText('No notifications yet')).toBeNull();
  });

  it('GivenAMarkAllReadWasRefused_WhenThePageShows_ThenItSaysWhyWithTheReference', () => {
    renderPage(aContext({ actionError: { message: 'Internal server error', traceId: 'trace-122' } }));

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Could not mark your notifications as read.');
    expect(alert.textContent).toContain('trace-122');
  });
});
