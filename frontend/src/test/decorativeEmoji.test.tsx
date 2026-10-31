import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import NavLinks from '../components/layout/NavLinks';
import NotificationBell from '../components/notifications/NotificationBell';
import NotificationDropdown from '../components/notifications/NotificationDropdown';
import NotificationItem from '../components/notifications/NotificationItem';
import ToastContainer from '../components/notifications/ToastContainer';
import EmptyState from '../components/ui/EmptyState';
import ErrorState from '../components/ui/ErrorState';
import UpgradeProgress from '../components/upgrade/UpgradeProgress';
import UpgradeReflections from '../components/upgrade/UpgradeReflections';
import UpgradeReminders from '../components/upgrade/UpgradeReminders';
import UpgradeStreak from '../components/upgrade/UpgradeStreak';
import ErrorBoundary from '../components/ErrorBoundary';
import { ThemeProvider } from '../contexts/ThemeProvider';
import LoginPage from '../pages/LoginPage';
import RegisterPage from '../pages/RegisterPage';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import { NotificationContext, type NotificationContextType } from '../contexts/notificationContextValue';
import { notificationAsSent, reflectionAsSent } from './wireFixtures';
import { silenceExpectedRenderErrors } from './expectedRenderErrors';

/**
 * An emoji, or one of the dingbat ticks and crosses this interface draws beside a word that already says
 * it. A screen reader names each ("check mark"), so each is decoration to hide.
 */
const DECORATIVE_SYMBOL = /[\p{Extended_Pictographic}\u2713\u2717]/u;

/** True when every emoji the element holds sits inside something hidden from assistive technology. */
function emojiAreHidden(element: Element): boolean {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (DECORATIVE_SYMBOL.test(node.textContent ?? '') && !node.parentElement?.closest('[aria-hidden="true"]')) {
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

/** A section of an upgrade's page, which reads and writes through the query client. */
const inSection = (section: React.ReactNode) => (
  <QueryClientProvider client={new QueryClient()}>{section}</QueryClientProvider>
);

/** A read that has answered, as an upgrade's page hands one to each section. */
const answered = <T,>(data: T) => ({ data, isLoading: false, error: null });

/** The pages outside the shell, which bring their own theme switch and read the session. */
const outsideTheShell = (page: React.ReactNode) => (
  <ThemeProvider>
    <AuthContext.Provider value={{ ...AUTH, user: null, isAuthenticated: false }}>
      <MemoryRouter>{page}</MemoryRouter>
    </AuthContext.Provider>
  </ThemeProvider>
);

const Throws: React.FC = () => {
  throw new Error('render failed');
};

/**
 * The emoji finding X9.24 left in place, each one read aloud before the text it decorates: "alarm clock,
 * 07:00", "star, 4/5", "check mark button, What worked:", "bell, Enable desktop notifications" (WCAG
 * 1.1.1, review finding 22 on #407).
 */
describe('decorative emoji, the rest', () => {
  it.each([
    [
      'AReminderRow',
      inSection(
        <UpgradeReminders
          upgradeId="u1"
          read={answered([{ id: 'r1', upgradeId: 'u1', reminderTime: '07:00:00', daysOfWeek: [], enabled: true }])}
        />,
      ),
    ],
    [
      'ARatedProgressEntry',
      inSection(
        <UpgradeProgress
          upgradeId="u1"
          trackingConfig={null}
          read={answered([
            {
              id: 'p1',
              upgradeId: 'u1',
              userId: 'u1',
              date: '2026-03-10',
              completed: null,
              numericValue: null,
              unit: null,
              rating: 4,
              note: null,
              createdAt: '2026-03-10T20:00:00',
            },
          ])}
        />,
      ),
    ],
    [
      'AReflection',
      inSection(
        <UpgradeReflections
          upgradeId="u1"
          read={answered([reflectionAsSent({ whatWorked: 'a', whatDidNotWork: 'b', nextAdjustment: 'c' })])}
        />,
      ),
    ],
    [
      'TheBell',
      <NotificationContext.Provider key="b" value={NOTIFICATIONS}>
        <MemoryRouter>
          <NotificationBell />
        </MemoryRouter>
      </NotificationContext.Provider>,
    ],
    [
      'TheOfferOfDesktopNotifications',
      <NotificationContext.Provider key="d" value={{ ...NOTIFICATIONS, desktopPermission: 'default' }}>
        <MemoryRouter>
          <NotificationDropdown id="panel" onClose={() => {}} onActed={() => {}} />
        </MemoryRouter>
      </NotificationContext.Provider>,
    ],
    ['TheSignInPage', outsideTheShell(<LoginPage />)],
    ['TheRegistrationPage', outsideTheShell(<RegisterPage />)],
  ])('Given%s_WhenItRenders_ThenItsEmojiAreHidden', (_, element) => {
    const { container } = render(element);

    expect(emojiAreHidden(container)).toBe(true);
  });

  it('GivenAPageThatStoppedWorking_WhenTheBoundaryShowsIt_ThenItsWarningSignIsHidden', () => {
    const unsilence = silenceExpectedRenderErrors();
    try {
      const { container } = render(
        <ErrorBoundary>
          <Throws />
        </ErrorBoundary>,
      );

      expect(emojiAreHidden(container)).toBe(true);
    } finally {
      unsilence();
    }
  });
});
