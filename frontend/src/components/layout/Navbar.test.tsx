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
 * Whether anything from `el` up to `<body>` is hidden at the narrowest width. Tailwind's breakpoint
 * variants only ever add display back, so a bare `hidden` on the element or an ancestor is what takes
 * it off a phone screen — and a class is the most jsdom, with no layout engine, can observe about it.
 */
function hiddenAtTheNarrowestWidth(el: HTMLElement): boolean {
  for (let node: HTMLElement | null = el; node && node !== document.body; node = node.parentElement) {
    if (node.classList.contains('hidden')) return true;
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
