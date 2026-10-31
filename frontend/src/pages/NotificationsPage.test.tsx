import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NotificationsPage from './NotificationsPage';
import { NotificationContext, type NotificationContextType } from '../contexts/notificationContextValue';
import { notificationAsSent } from '../test/wireFixtures';
import type { AppNotification } from '../types';

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
    toasts: [],
    dismissToast: vi.fn(),
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

  it('GivenAListIsCachedAndARefetchFailed_WhenThePageShows_ThenItSaysSoAboveTheList', () => {
    // The cached list stays, since it is still the best there is, but without the failure it read as
    // current, under "You are all caught up".
    const cached = notificationAsSent({ id: 'n-1', title: 'Streak kept', read: true });
    renderPage(
      aContext({ notifications: [cached], loadError: { message: 'Internal server error', traceId: 'trace-123' } }),
    );

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('trace-123');
    expect(screen.getByText('Streak kept')).toBeDefined();
  });

  it('GivenANotificationOfACategoryThisBuildDoesNotKnow_WhenThePageShows_ThenItIsListed', () => {
    // Read without a fallback, its icon threw during render, and the error boundary blanked the app.
    const unknown = { ...notificationAsSent({ id: 'n-1', title: 'Something new' }), category: 'URGENT' };

    renderPage(aContext({ notifications: [unknown as unknown as AppNotification] }));

    expect(screen.getByText('Something new')).toBeDefined();
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('NotificationsPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';

    renderPage(aContext());

    expect(document.title).toBe('Notifications – UpHealther');
  });
});

/**
 * "Mark all read" and "Enable desktop alerts" each disappear once used, so the button that had focus
 * went with them, and focus fell to `<body>`. It goes to the page's heading instead (WCAG 2.4.3,
 * finding X9.4).
 */
describe('NotificationsPage focus', () => {
  /** A context whose actions change what it holds, as the provider's do. */
  function StatefulPage() {
    const [unread, setUnread] = useState(2);
    const [permission, setPermission] = useState<NotificationPermission>('default');
    return (
      <NotificationContext.Provider
        value={aContext({
          unreadCount: unread,
          desktopPermission: permission,
          markAllRead: () => setUnread(0),
          requestDesktopPermission: () => setPermission('granted'),
        })}
      >
        <MemoryRouter>
          <NotificationsPage />
        </MemoryRouter>
      </NotificationContext.Provider>
    );
  }

  it.each(['Mark all read', 'Enable desktop alerts'])(
    'GivenFocusOn%s_WhenItIsUsedAndGoes_ThenFocusMovesToThePageHeading',
    async (action) => {
      render(<StatefulPage />);
      const button = screen.getByRole('button', { name: action });
      button.focus();

      fireEvent.click(button);

      expect(screen.queryByRole('button', { name: action })).toBeNull();
      await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })));
    },
  );
});
