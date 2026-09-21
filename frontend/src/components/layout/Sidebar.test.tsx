import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Sidebar from './Sidebar';
import { AuthContext, type AuthContextType } from '../../contexts/authContextValue';
import type { User } from '../../types';

const ADMIN: User = {
  id: 'admin-1',
  name: 'An Administrator',
  email: 'admin@example.com',
  role: 'ADMIN',
  createdAt: '2026-03-15T09:00:00',
};

const ORDINARY: User = { ...ADMIN, id: 'user-1', email: 'someone@example.com', role: 'USER' };

function renderSidebar(user: User | null) {
  const ctx: AuthContextType = {
    user,
    isLoading: false,
    isAuthenticated: user !== null,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
  };
  return render(
    <AuthContext.Provider value={ctx}>
      <MemoryRouter>
        <Sidebar />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/**
 * The navigation, and the one entry that is conditional.
 *
 * Hiding the Accounts link from an ordinary user is tidiness, not security — the page and every request
 * behind it are refused by the server regardless. It is tested anyway for the opposite reason to the
 * usual one: not because hiding it protects anything, but because *showing* it to somebody who cannot
 * use it walks them into a dead end, and because an administrator who cannot find the page has, in
 * effect, no administration at all.
 */
describe('Sidebar', () => {
  it('GivenAnOrdinaryUser_WhenTheSidebarRenders_ThenThereIsNoAccountsLink', () => {
    renderSidebar(ORDINARY);

    expect(screen.queryByText('Accounts')).toBeNull();
  });

  it('GivenAnAdministrator_WhenTheSidebarRenders_ThenTheAccountsLinkIsThere', () => {
    renderSidebar(ADMIN);

    const link = screen.getByText('Accounts').closest('a');
    expect(link).not.toBeNull();
    expect(link?.getAttribute('href')).toBe('/admin/users');
  });

  it('GivenAnAdministrator_WhenTheSidebarRenders_ThenTheOrdinaryEntriesAreStillThere', () => {
    // The admin entry is appended, not substituted: an administrator is a user first.
    renderSidebar(ADMIN);

    expect(screen.getByText('Dashboard')).toBeDefined();
    expect(screen.getByText('Daily Check-in')).toBeDefined();
    expect(screen.getByText('Accounts')).toBeDefined();
  });

  it('GivenNoUserYet_WhenTheSidebarRenders_ThenNoAccountsLinkIsShown', () => {
    // The window while the session is being restored. Showing it optimistically would flash an entry
    // that then disappears for everybody who is not an administrator.
    renderSidebar(null);

    expect(screen.queryByText('Accounts')).toBeNull();
    expect(screen.getByText('Dashboard')).toBeDefined();
  });
});
