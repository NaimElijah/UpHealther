import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { notificationAsSent } from '../../test/wireFixtures';
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
    toasts: [],
    dismissToast: () => {},
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

/** A context whose actions change what it holds, as the provider's do, around a bell inside a router. */
function StatefulBell() {
  const [unread, setUnread] = useState(2);
  const [permission, setPermission] = useState<NotificationPermission>('default');
  const value: NotificationContextType = {
    notifications: [notificationAsSent({ id: 'n1', title: 'Time to log', relatedUpgradeId: null, read: false })],
    unreadCount: unread,
    connected: true,
    desktopPermission: permission,
    markRead: () => setUnread((n) => n - 1),
    markAllRead: () => setUnread(0),
    requestDesktopPermission: () => setPermission('granted'),
    toasts: [],
    dismissToast: () => {},
  };
  return (
    <NotificationContext.Provider value={value}>
      <MemoryRouter>
        <NotificationBell />
      </MemoryRouter>
    </NotificationContext.Provider>
  );
}

function openPanel() {
  render(<StatefulBell />);
  const bell = screen.getByRole('button', { name: /^Notifications/ });
  bell.focus();
  fireEvent.click(bell);
  return bell;
}

/**
 * The bell is a disclosure, as the WAI-ARIA Authoring Practices describe one: it says whether its panel
 * is open and which panel it opens, Escape closes the panel, and focus comes back to the bell rather than
 * falling to `<body>` when the panel closes or an action in it removes the control that had focus
 * (WCAG 4.1.2, 2.4.3, finding X9.12).
 */
describe('NotificationBell disclosure', () => {
  it('GivenTheBell_WhenItIsPressed_ThenItSaysItIsExpandedAndNamesThePanelItControls', () => {
    render(<StatefulBell />);
    const bell = screen.getByRole('button', { name: /^Notifications/ });
    expect(bell.getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(bell);

    expect(bell.getAttribute('aria-expanded')).toBe('true');
    const panel = document.getElementById(bell.getAttribute('aria-controls') ?? '');
    expect(panel?.textContent).toContain('View all notifications');
  });

  it('GivenThePanelIsOpen_WhenEscapeIsPressed_ThenItClosesAndTheBellHasFocus', () => {
    const bell = openPanel();
    screen.getByRole('link', { name: 'View all notifications' }).focus();

    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });

    expect(screen.queryByRole('link', { name: 'View all notifications' })).toBeNull();
    expect(bell.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(bell);
  });

  it.each(['Mark all read', /Enable desktop notifications/])(
    'GivenThePanelIsOpen_When%sIsUsedAndGoes_ThenFocusReturnsToTheBell',
    (name) => {
      const bell = openPanel();
      const action = screen.getByRole('button', { name });
      action.focus();

      fireEvent.click(action);

      expect(screen.queryByRole('button', { name })).toBeNull();
      expect(document.activeElement).toBe(bell);
    },
  );

  // The documented outside-click close was untested (review finding 20 on #407).
  it('GivenThePanelIsOpen_WhenThePointerPressesOutsideIt_ThenItClosesAndFocusStaysWhereTheClickPutIt', () => {
    render(
      <>
        <StatefulBell />
        <button type="button">Elsewhere</button>
      </>,
    );
    const bell = screen.getByRole('button', { name: /^Notifications/ });
    fireEvent.click(bell);
    const elsewhere = screen.getByRole('button', { name: 'Elsewhere' });
    elsewhere.focus();

    fireEvent.mouseDown(elsewhere);

    expect(bell.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('button', { name: /Time to log/ })).toBeNull();
    expect(document.activeElement).toBe(elsewhere);
  });

  it('GivenThePanelIsOpen_WhenThePointerPressesInsideIt_ThenItStaysOpen', () => {
    const bell = openPanel();

    fireEvent.mouseDown(screen.getByRole('button', { name: /Time to log/ }));

    expect(bell.getAttribute('aria-expanded')).toBe('true');
  });

  it('GivenANotificationWithNoUpgrade_WhenItIsSelected_ThenThePanelClosesAndTheBellHasFocus', () => {
    const bell = openPanel();
    const item = screen.getByRole('button', { name: /Time to log/ });
    item.focus();

    fireEvent.click(item);

    expect(bell.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(bell);
  });
});
