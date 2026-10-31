import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NavLinks from '../components/layout/NavLinks';
import NotificationItem from '../components/notifications/NotificationItem';
import ToastContainer from '../components/notifications/ToastContainer';
import EmptyState from '../components/ui/EmptyState';
import ErrorState from '../components/ui/ErrorState';
import UpgradeStreak from '../components/upgrade/UpgradeStreak';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import { NotificationContext, type NotificationContextType } from '../contexts/notificationContextValue';
import { notificationAsSent } from './wireFixtures';

/** True when every emoji the element holds sits inside something hidden from assistive technology. */
function emojiAreHidden(element: Element): boolean {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (
      /\p{Extended_Pictographic}/u.test(node.textContent ?? '') &&
      !node.parentElement?.closest('[aria-hidden="true"]')
    ) {
      return false;
    }
  }
  return true;
}

const AUTH: AuthContextType = {
  user: { id: 'u1', name: 'Ada', email: 'ada@example.com', role: 'USER', createdAt: '2026-01-01T00:00:00' },
  isLoading: false,
  isAuthenticated: true,
  login: async () => {},
  register: async () => {},
  logout: async () => {},
};

const NOTIFICATIONS: NotificationContextType = {
  notifications: [],
  unreadCount: 0,
  connected: true,
  desktopPermission: 'granted',
  markRead: () => {},
  markAllRead: () => {},
  requestDesktopPermission: () => {},
  toasts: [{ id: 't1', category: 'SUCCESS', title: 'Upgrade activated', message: null, relatedUpgradeId: null }],
  dismissToast: () => {},
};

/**
 * The emoji that stand in for an icon are hidden from assistive technology, so they are not read as part
 * of a link's, a button's or a heading's name: a notification read "information, Upgrade activated…"
 * and a navigation link "house, Dashboard" (WCAG 1.1.1, finding X9.24). An emoji that is the user's own
 * data, such as one typed into a title, is not decoration and is left alone.
 */
describe('decorative emoji', () => {
  it('GivenTheNavigation_WhenItRenders_ThenEachLinkIsNamedByItsLabelAlone', () => {
    render(
      <AuthContext.Provider value={AUTH}>
        <MemoryRouter>
          <NavLinks />
        </MemoryRouter>
      </AuthContext.Provider>,
    );

    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeDefined();
    expect(emojiAreHidden(screen.getByRole('navigation'))).toBe(true);
  });

  it('GivenANotificationRow_WhenItRenders_ThenItsCategoryIconIsHidden', () => {
    render(
      <NotificationItem
        notification={notificationAsSent({ title: 'Upgrade activated', read: true })}
        onSelect={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: /^Upgrade activated/ })).toBeDefined();
  });

  it('GivenAToast_WhenItRenders_ThenItsCategoryIconIsHidden', () => {
    render(
      <NotificationContext.Provider value={NOTIFICATIONS}>
        <MemoryRouter>
          <ToastContainer />
        </MemoryRouter>
      </NotificationContext.Provider>,
    );

    expect(screen.getByRole('button', { name: /^Upgrade activated/ })).toBeDefined();
  });

  it.each([
    ['AnEmptyState', <EmptyState key="e" title="Nothing here" />],
    ['AFailedLoad', <ErrorState key="f" title="Could not load this." />],
    [
      'ARunningStreak',
      <UpgradeStreak key="s" read={{ data: { current: 3, longest: 5 }, isLoading: false, error: null }} />,
    ],
  ])('Given%s_WhenItRenders_ThenItsEmojiAreHidden', (_, element) => {
    const { container } = render(element);

    expect(emojiAreHidden(container)).toBe(true);
  });
});
