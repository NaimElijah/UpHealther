import type { ReactNode } from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import AppRouter from './index';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import { navItemsFor } from '../components/layout/navItems';
import type { User } from '../types';

// The pages a test can reach, the frame, the notification socket and the toasts it feeds are replaced:
// what is under test is which path reaches which page through which gate, and each of those has its own
// tests. Each stub renders its page's name, so the test reads which one a URL reached. The other pages
// are left real: no test here opens a path that renders them.
vi.mock('../components/layout/Layout', () => ({ default: ({ children }: { children: ReactNode }) => children }));
vi.mock('../contexts/NotificationProvider', () => ({
  NotificationProvider: ({ children }: { children: ReactNode }) => children,
}));
vi.mock('../components/notifications/ToastContainer', () => ({ default: () => <aside>the toasts</aside> }));
vi.mock('../pages/LoginPage', () => ({ default: () => 'the login page' }));
vi.mock('../pages/DashboardPage', () => ({ default: () => 'the dashboard page' }));
vi.mock('../pages/HealthAreasPage', () => ({ default: () => 'the health areas page' }));
vi.mock('../pages/UpgradeBacklogPage', () => ({ default: () => 'the idea backlog page' }));
vi.mock('../pages/PlannedUpgradesPage', () => ({ default: () => 'the planned upgrades page' }));
vi.mock('../pages/ActiveUpgradesPage', () => ({ default: () => 'the active upgrades page' }));
vi.mock('../pages/DailyCheckinPage', () => ({ default: () => 'the daily check-in page' }));
vi.mock('../pages/ProgressHistoryPage', () => ({ default: () => 'the progress history page' }));
vi.mock('../pages/NotificationsPage', () => ({ default: () => 'the notifications page' }));
vi.mock('../pages/AdminUsersPage', () => ({ default: () => 'the accounts page' }));

const ADMIN: User = {
  id: 'admin-1',
  name: 'An Administrator',
  email: 'admin@example.com',
  role: 'ADMIN',
  createdAt: '2026-03-15T09:00:00',
};

const ORDINARY: User = { ...ADMIN, id: 'user-1', email: 'someone@example.com', role: 'USER' };

/** Every path the real table puts behind `ProtectedRoute`; `/upgrades/:id` is opened with a made-up id. */
const PROTECTED_PATHS = [
  '/',
  '/dashboard',
  '/health-areas',
  '/upgrades/backlog',
  '/upgrades/active',
  '/upgrades/planned',
  '/upgrades/upgrade-1',
  '/daily-checkin',
  '/progress-history',
  '/notifications',
  '/admin/users',
];

/** An auth context in a chosen state, so the routes can be tested without driving a real session. */
function contextWith(overrides: Partial<AuthContextType>): AuthContextType {
  return {
    user: null,
    isLoading: false,
    isAuthenticated: false,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
    ...overrides,
  };
}

/** Opens the real route table at `path`: it builds its own `BrowserRouter`, which reads the window's URL. */
function visit(path: string, ctx: AuthContextType) {
  window.history.pushState({}, '', path);
  return render(
    <AuthContext.Provider value={ctx}>
      <AppRouter />
    </AuthContext.Provider>,
  );
}

/**
 * The route table's gates: which wrapper stands in front of which path, FR-46's administration page included.
 *
 * `ProtectedRoute` and `RequireRole` are each tested on their own, against a route table the test builds.
 * Nothing checked that the real table puts them where they belong, so dropping `RequireRole` from
 * `/admin/users` would have passed every test while offering the administration page to everybody. The
 * API still refuses the requests behind it; the gate is the courtesy that spares a page of failures.
 */
describe('AppRouter', () => {
  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it('GivenAnOrdinaryUser_WhenTheyOpenTheAdministrationPage_ThenTheyLandOnTheirDashboard', async () => {
    visit('/admin/users', contextWith({ isAuthenticated: true, user: ORDINARY }));

    expect(await screen.findByText('the dashboard page')).toBeDefined();
    expect(screen.queryByText('the accounts page')).toBeNull();
    expect(window.location.pathname).toBe('/dashboard');
  });

  it('GivenAnAdministrator_WhenTheyOpenTheAdministrationPage_ThenItRenders', async () => {
    visit('/admin/users', contextWith({ isAuthenticated: true, user: ADMIN }));

    expect(await screen.findByText('the accounts page')).toBeDefined();
  });

  it('GivenAnAnonymousVisitor_WhenTheyOpenTheAdministrationPage_ThenTheyAreSentToSignIn', async () => {
    visit('/admin/users', contextWith({}));

    expect(await screen.findByText('the login page')).toBeDefined();
    expect(window.location.pathname).toBe('/login');
  });

  it('GivenAnAnonymousVisitor_WhenTheyOpenTheDashboard_ThenTheyAreSentToSignIn', async () => {
    visit('/dashboard', contextWith({}));

    expect(await screen.findByText('the login page')).toBeDefined();
    expect(screen.queryByText('the dashboard page')).toBeNull();
    expect(window.location.pathname).toBe('/login');
  });

  it.each(PROTECTED_PATHS)('GivenAnAnonymousVisitor_WhenTheyOpen %s_ThenTheyAreSentToSignIn', async (path) => {
    visit(path, contextWith({}));

    expect(await screen.findByText('the login page')).toBeDefined();
    expect(window.location.pathname).toBe('/login');
  });

  it('GivenASignedInUser_WhenTheyOpenAnUnknownPath_ThenTheyLandOnTheirDashboard', async () => {
    visit('/no-such-page', contextWith({ isAuthenticated: true, user: ORDINARY }));

    expect(await screen.findByText('the dashboard page')).toBeDefined();
    expect(window.location.pathname).toBe('/dashboard');
  });
});

/** The page each navigation entry is meant to open, by the name its stub above renders. */
const PAGE_AT: Record<string, string> = {
  '/dashboard': 'the dashboard page',
  '/health-areas': 'the health areas page',
  '/upgrades/backlog': 'the idea backlog page',
  '/upgrades/planned': 'the planned upgrades page',
  '/upgrades/active': 'the active upgrades page',
  '/daily-checkin': 'the daily check-in page',
  '/progress-history': 'the progress history page',
  '/notifications': 'the notifications page',
  '/admin/users': 'the accounts page',
};

/**
 * The navigation against the route table: every entry the sidebar and the phone menu offer is a path the
 * table serves, and not one the catch-all sends to the dashboard.
 *
 * The two are written apart, `navItems.ts` and `router/index.tsx`, so a path renamed in one and not the
 * other turns its link into a way to the dashboard, which nothing else notices. The administrator's
 * entries include everyone else's. Both the page and the URL are checked: the URL alone cannot tell the
 * dashboard's entry from the catch-all's redirect to it.
 */
describe('AppRouter navigation targets', () => {
  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it.each(navItemsFor('ADMIN').map((item) => item.to))(
    'GivenAnAdministrator_WhenTheyFollowTheNavigationTo %s_ThenTheRouteTableServesItsPage',
    async (to) => {
      expect(PAGE_AT, `no page is named for the navigation entry ${to}; add it to PAGE_AT`).toHaveProperty([to]);

      visit(to, contextWith({ isAuthenticated: true, user: ADMIN }));

      expect(await screen.findByText(PAGE_AT[to])).toBeDefined();
      expect(window.location.pathname).toBe(to);
    },
  );
});

/**
 * Where the toasts are mounted: once, beside the routes, so neither a page nor the shared frame owns them
 * and they are there whether or not anybody is signed in. `NotificationProvider` holds them and
 * `ToastContainer` draws them, so nothing else fails if the route table stops mounting it.
 */
describe('AppRouter toast layer', () => {
  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it('GivenAnAnonymousVisitor_WhenTheyOpenSignIn_ThenTheToastLayerIsMounted', async () => {
    visit('/login', contextWith({}));

    expect(await screen.findByText('the login page')).toBeDefined();
    expect(screen.getAllByText('the toasts')).toHaveLength(1);
  });

  it('GivenASignedInUser_WhenTheyOpenTheirDashboard_ThenTheToastLayerIsMounted', async () => {
    visit('/dashboard', contextWith({ isAuthenticated: true, user: ORDINARY }));

    expect(await screen.findByText('the dashboard page')).toBeDefined();
    expect(screen.getAllByText('the toasts')).toHaveLength(1);
  });
});
