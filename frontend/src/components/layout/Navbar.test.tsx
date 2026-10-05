import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Navbar from './Navbar';
import { AuthContext, type AuthContextType } from '../../contexts/authContextValue';
import { NotificationContext, type NotificationContextType } from '../../contexts/notificationContextValue';
import { ThemeContext, type ThemeContextType } from '../../contexts/themeContextValue';
import type { User } from '../../types';

const USER: User = {
  id: 'user-1',
  name: 'Someone',
  email: 'someone@example.com',
  role: 'USER',
  createdAt: '2026-03-15T09:00:00',
};

const THEME: ThemeContextType = { theme: 'light', resolvedTheme: 'light', setTheme: () => {} };

const NOTIFICATIONS: NotificationContextType = {
  notifications: [],
  unreadCount: 0,
  connected: true,
  desktopPermission: 'default',
  markRead: () => {},
  markAllRead: () => {},
  requestDesktopPermission: () => {},
};

function renderNavbar(logout: () => Promise<void>) {
  const auth: AuthContextType = {
    user: USER,
    isLoading: false,
    isAuthenticated: true,
    login: async () => {},
    register: async () => {},
    logout,
  };
  return render(
    <ThemeContext.Provider value={THEME}>
      <AuthContext.Provider value={auth}>
        <NotificationContext.Provider value={NOTIFICATIONS}>
          <MemoryRouter>
            <Navbar />
          </MemoryRouter>
        </NotificationContext.Provider>
      </AuthContext.Provider>
    </ThemeContext.Provider>,
  );
}

/**
 * Every class form that takes an element off a phone screen: `hidden`, `invisible` or `sr-only`, bare
 * or under a `max-*` variant (`max-sm:hidden`, `max-[400px]:invisible`). A `min-*` or plain responsive
 * variant (`sm:hidden`) only hides above the narrowest width, so it does not count.
 */
const HIDES_ON_A_PHONE = /^(?:max-[^:]+:)?(?:hidden|invisible|sr-only)$/;

/**
 * Whether anything from `el` up to `<body>` hides it at the narrowest width.
 *
 * This reads class names, which is an implementation detail, and it does so knowingly: jsdom has no
 * layout engine and runs no Tailwind, so whether a phone *shows* the alert is beyond any test here and
 * is checked in a browser (§6). What a class check can still catch is the regression's own shape —
 * `hidden sm:block` is how #97 came about — so it covers every form of it rather than the one literal.
 */
function hiddenAtTheNarrowestWidth(el: HTMLElement): boolean {
  for (let node: HTMLElement | null = el; node && node !== document.body; node = node.parentElement) {
    if (Array.from(node.classList).some((cls) => HIDES_ON_A_PHONE.test(cls))) return true;
  }
  return false;
}

/**
 * FR-48: a sign-out that fails says so. The failure matters most where the window is smallest — a
 * phone, a shared tablet — so it has to be said at every width (#97).
 */
describe('Navbar', () => {
  it('GivenASignOutThatFails_WhenLogoutIsPressed_ThenTheFailureIsShownAtEveryWidth', async () => {
    renderNavbar(() => Promise.reject(new Error('Network Error')));

    fireEvent.click(screen.getByRole('button', { name: 'Logout' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Could not sign out — you are still signed in.');
    expect(hiddenAtTheNarrowestWidth(alert)).toBe(false);
  });

  it('GivenASignOutThatSucceeds_WhenLogoutIsPressed_ThenNoFailureIsShown', async () => {
    let signedOut = false;
    renderNavbar(async () => {
      signedOut = true;
    });

    fireEvent.click(screen.getByRole('button', { name: 'Logout' }));

    await waitFor(() => expect(signedOut).toBe(true));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
