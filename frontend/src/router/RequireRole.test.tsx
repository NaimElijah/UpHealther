import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import RequireRole from './RequireRole';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import type { User } from '../types';

const ADMIN: User = {
  id: 'admin-1',
  name: 'An Administrator',
  email: 'admin@example.com',
  role: 'ADMIN',
  createdAt: '2026-03-15T09:00:00',
};

const ORDINARY: User = { ...ADMIN, id: 'user-1', email: 'someone@example.com', role: 'USER' };

/** An auth context in a chosen state, so the gate can be tested without driving a real session. */
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

function renderGate(ctx: AuthContextType) {
  return render(
    <AuthContext.Provider value={ctx}>
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route path="/dashboard" element={<div>the dashboard</div>} />
          <Route
            path="/admin/users"
            element={
              <RequireRole role="ADMIN">
                <div>the accounts page</div>
              </RequireRole>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/**
 * The client-side half of role gating — and it is worth being clear, in the tests as much as in the
 * component, that this gate enforces nothing.
 *
 * Every path behind it is refused by the server with a 403, decided by the role read from the account
 * on that request. What this gate buys is that somebody who is not an administrator lands on their
 * dashboard instead of a page of failed requests. These tests pin that courtesy; `AdminUserControllerTest`
 * and `AuthenticatedBoundaryTest` pin the actual wall.
 *
 * The loading state is the one that bites. The session is restored asynchronously from the refresh
 * cookie, so there is a window where the role is not yet known — and redirecting during it bounces an
 * administrator off their own page on every single refresh, a bug that disappears whenever the API
 * happens to answer quickly.
 */
describe('RequireRole', () => {
  it('GivenAnAdministrator_WhenTheAdminPageIsVisited_ThenItRenders', () => {
    renderGate(contextWith({ isAuthenticated: true, user: ADMIN }));

    expect(screen.getByText('the accounts page')).toBeDefined();
  });

  it('GivenAnOrdinaryUser_WhenTheAdminPageIsVisited_ThenTheyAreSentToTheirDashboard', () => {
    renderGate(contextWith({ isAuthenticated: true, user: ORDINARY }));

    expect(screen.getByText('the dashboard')).toBeDefined();
    expect(screen.queryByText('the accounts page')).toBeNull();
  });

  it('GivenASessionStillBeingRestored_WhenTheAdminPageIsVisited_ThenNeitherIsShownYet', () => {
    // Redirecting here would bounce an administrator off their own page on every reload, because the
    // role is not known until the refresh cookie has been exchanged.
    renderGate(contextWith({ isLoading: true, user: null }));

    expect(screen.queryByText('the dashboard')).toBeNull();
    expect(screen.queryByText('the accounts page')).toBeNull();
  });

  it('GivenNoUserAtAllOnceLoadingHasFinished_WhenTheAdminPageIsVisited_ThenItIsNotRendered', () => {
    // Reached only if this gate is ever used without ProtectedRoute around it. Failing closed costs
    // nothing; failing open would render an administration page to an anonymous caller.
    renderGate(contextWith({ isLoading: false, user: null }));

    expect(screen.queryByText('the accounts page')).toBeNull();
  });
});
