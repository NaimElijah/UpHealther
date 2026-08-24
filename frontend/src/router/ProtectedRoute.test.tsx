import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ProtectedRoute from './ProtectedRoute';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';

/** An auth context in a chosen state, so the gate can be tested without driving a real session. */
function contextWith(overrides: Partial<AuthContextType>): AuthContextType {
  return {
    user: null,
    token: null,
    isLoading: false,
    isAuthenticated: false,
    login: async () => {},
    register: async () => {},
    logout: () => {},
    ...overrides,
  };
}

function renderGate(ctx: AuthContextType) {
  return render(
    <AuthContext.Provider value={ctx}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/login" element={<div>the login page</div>} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <div>the dashboard</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/**
 * FR-5's client half: a protected page renders only for a signed-in caller.
 *
 * The interesting state is the third one. A stored token is verified asynchronously on load, so there
 * is a window where the caller is neither known-signed-in nor known-signed-out. Treating that window as
 * "not authenticated" bounces a perfectly valid session to the login page on every refresh — a bug that
 * looks like a flaky redirect and is invisible whenever the API answers quickly.
 */
describe('ProtectedRoute', () => {
  it('GivenASignedInCaller_WhenAProtectedPageIsVisited_ThenThePageRenders', () => {
    renderGate(contextWith({ isAuthenticated: true, token: 'stored.token' }));

    expect(screen.getByText('the dashboard')).toBeDefined();
  });

  it('GivenAnAnonymousCaller_WhenAProtectedPageIsVisited_ThenTheyAreSentToLogin', () => {
    renderGate(contextWith({ isAuthenticated: false }));

    expect(screen.getByText('the login page')).toBeDefined();
    expect(screen.queryByText('the dashboard')).toBeNull();
  });

  it('GivenAStoredTokenStillBeingVerified_WhenAProtectedPageIsVisited_ThenNeitherIsShownYet', () => {
    renderGate(contextWith({ isLoading: true, isAuthenticated: false }));

    expect(screen.queryByText('the login page')).toBeNull();
    expect(screen.queryByText('the dashboard')).toBeNull();
  });
});
