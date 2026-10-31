import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NotificationDropdown from './NotificationDropdown';
import { NotificationContext, type NotificationContextType } from '../../contexts/notificationContextValue';
import { notificationAsSent } from '../../test/wireFixtures';

/** The context the panel reads, with nothing in it unless a test says otherwise. */
function aContext(overrides: Partial<NotificationContextType> = {}): NotificationContextType {
  return {
    notifications: [],
    unreadCount: 0,
    connected: true,
    desktopPermission: 'granted',
    markRead: vi.fn(),
    markAllRead: vi.fn(),
    requestDesktopPermission: vi.fn(),
    toasts: [],
    dismissToast: vi.fn(),
    ...overrides,
  };
}

function renderPanel(context: NotificationContextType) {
  return render(
    <NotificationContext.Provider value={context}>
      <MemoryRouter>
        <NotificationDropdown id="panel" onClose={vi.fn()} onActed={vi.fn()} />
      </MemoryRouter>
    </NotificationContext.Provider>,
  );
}

/**
 * NFR-30 (#96) — the bell's panel says when its list could not be fetched, or a mark-read was refused,
 * with the trace id that finds it. Before, a failed fetch read "No notifications yet."
 */
describe('NotificationDropdown', () => {
  it('GivenTheListCouldNotBeFetched_WhenThePanelOpens_ThenItSaysSoWithTheReferenceRatherThanNoNotifications', () => {
    renderPanel(aContext({ loadError: { message: 'Internal server error', traceId: 'trace-111' } }));

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Could not load your notifications.');
    expect(alert.textContent).toContain('trace-111');
    expect(screen.queryByText('No notifications yet.')).toBeNull();
  });

  it('GivenAMarkReadWasRefused_WhenThePanelOpens_ThenItSaysWhyWithTheReference', () => {
    // A mark-read from this panel closes it and navigates, so the refusal is shown the next time it opens.
    renderPanel(aContext({ actionError: { message: 'Internal server error', traceId: 'trace-112' } }));

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Could not mark your notifications as read.');
    expect(alert.textContent).toContain('trace-112');
  });

  it('GivenAListIsCachedAndARefetchFailed_WhenThePanelOpens_ThenItSaysSoAboveTheList', () => {
    // The cached rows stay, since they are still the best there is, but without the failure they read
    // as current.
    const cached = notificationAsSent({ id: 'n-1', title: 'Streak kept', read: true });
    renderPanel(
      aContext({ notifications: [cached], loadError: { message: 'Internal server error', traceId: 'trace-113' } }),
    );

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('trace-113');
    expect(screen.getByText('Streak kept')).toBeDefined();
  });
});

/**
 * "View all notifications" only goes to another page, so it is a link, which can be opened in a new tab
 * and is announced as one; it was a button that navigated (review finding 30 on #407).
 */
describe('NotificationDropdown View all', () => {
  it('GivenThePanel_WhenItRenders_ThenViewAllIsALinkToTheNotificationsPage', () => {
    renderPanel(aContext());

    expect(screen.getByRole('link', { name: 'View all notifications' }).getAttribute('href')).toBe('/notifications');
  });
});
