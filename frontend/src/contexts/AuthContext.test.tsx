import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import { AuthProvider } from './AuthContext';
import { useAuth } from '../hooks/useAuth';
import type { User } from '../types';

const getMe = vi.fn();
const loginApi = vi.fn();
const registerApi = vi.fn();

vi.mock('../api/auth', () => ({
  getMe: (...args: unknown[]) => getMe(...args),
  loginApi: (...args: unknown[]) => loginApi(...args),
  registerApi: (...args: unknown[]) => registerApi(...args),
}));

const USER: User = {
  id: 'user-1',
  name: 'Someone',
  email: 'someone@example.com',
  createdAt: '2026-03-15T09:00:00',
};

/** Renders the provider's state as text, so a test asserts through what a consumer would actually see. */
function Probe() {
  const { user, isAuthenticated, isLoading, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(isLoading)}</span>
      <span data-testid="authenticated">{String(isAuthenticated)}</span>
      <span data-testid="user">{user?.email ?? 'none'}</span>
      {/* Swallowed the way LoginPage does: it catches the rejection to show an error in the form. */}
      <button onClick={() => void login('someone@example.com', 's3cret!').catch(() => {})}>sign in</button>
      <button onClick={logout}>sign out</button>
    </div>
  );
}

const renderProvider = () =>
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );

/**
 * FR-3 — a stored token restores a session — and the session actions around it.
 *
 * The restore path is the one worth testing, and the only one impossible to check by clicking: the
 * cached user is shown immediately so the page has something to render, and is *simultaneously*
 * revalidated against `/api/auth/me`. That makes the cache a convenience rather than proof, and the
 * difference only shows when the server disagrees — a token the backend has stopped accepting has to
 * end the session rather than leave a stale name in the corner of the screen.
 *
 * `window.location` is redefined because logout performs a full document navigation, which jsdom
 * refuses to do.
 */
describe('AuthProvider', () => {
  const realLocation = window.location;

  beforeEach(() => {
    getMe.mockReset();
    loginApi.mockReset();
    registerApi.mockReset();
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...realLocation, href: '' },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  describe('restoring a session on load', () => {
    it('GivenNoStoredToken_WhenTheProviderMounts_ThenItSettlesAnonymousWithoutCallingTheApi', async () => {
      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(getMe).not.toHaveBeenCalled();
    });

    it('GivenAStoredTokenTheServerAccepts_WhenTheProviderMounts_ThenTheSessionIsRestored', async () => {
      localStorage.setItem('jwt_token', 'stored.token');
      getMe.mockResolvedValue(USER);

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('true');
      expect(screen.getByTestId('user').textContent).toBe(USER.email);
    });

    it('GivenACachedUser_WhenTheProviderMounts_ThenItIsShownBeforeTheServerHasAnswered', async () => {
      // The reason the cache exists: without it the page renders signed-out for one round trip and the
      // whole shell flashes.
      localStorage.setItem('jwt_token', 'stored.token');
      localStorage.setItem('user', JSON.stringify(USER));
      getMe.mockReturnValue(new Promise(() => {})); // never settles

      renderProvider();

      expect(screen.getByTestId('user').textContent).toBe(USER.email);
      expect(screen.getByTestId('loading').textContent).toBe('true');
    });

    it('GivenAStoredTokenTheServerRejects_WhenTheProviderMounts_ThenTheSessionIsCleared', async () => {
      // The cached user must not outlive the token it was cached with, or a signed-out user keeps
      // seeing their name and a shell they cannot use.
      localStorage.setItem('jwt_token', 'expired.token');
      localStorage.setItem('user', JSON.stringify(USER));
      getMe.mockRejectedValue(new Error('401'));

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(screen.getByTestId('user').textContent).toBe('none');
      expect(localStorage.getItem('jwt_token')).toBeNull();
      expect(localStorage.getItem('user')).toBeNull();
    });

    it('GivenACachedUserThatWillNotParse_WhenTheProviderMounts_ThenItIsDiscardedRatherThanCrashing', async () => {
      // Storage is writable by anything on the origin and survives a deploy that changed the shape.
      // An unguarded JSON.parse here blanks the entire application, since nothing sits above it.
      localStorage.setItem('jwt_token', 'stored.token');
      localStorage.setItem('user', '{not json');
      getMe.mockResolvedValue(USER);

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('user').textContent).toBe(USER.email);
    });

    it('GivenAStoredTokenTheServerAccepts_WhenTheProviderMounts_ThenTheCachedUserIsRefreshed', async () => {
      // A name changed on another device has to win over the copy in storage.
      localStorage.setItem('jwt_token', 'stored.token');
      localStorage.setItem('user', JSON.stringify({ ...USER, name: 'Stale Name' }));
      getMe.mockResolvedValue(USER);

      renderProvider();

      await waitFor(() =>
        expect(JSON.parse(localStorage.getItem('user') ?? '{}').name).toBe(USER.name),
      );
    });
  });

  describe('signing in and out', () => {
    it('GivenValidCredentials_WhenTheUserSignsIn_ThenTheTokenAndProfileArePersisted', async () => {
      loginApi.mockResolvedValue({ token: 'issued.token', user: USER });
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));

      await act(async () => {
        screen.getByText('sign in').click();
      });

      expect(localStorage.getItem('jwt_token')).toBe('issued.token');
      expect(screen.getByTestId('authenticated').textContent).toBe('true');
    });

    it('GivenCredentialsTheServerRejects_WhenTheUserSignsIn_ThenNothingIsPersisted', async () => {
      // A failed sign-in must leave no half-session behind — a token written before the response would
      // authenticate later requests as nobody.
      loginApi.mockRejectedValue(new Error('401'));
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));

      await act(async () => {
        screen.getByText('sign in').click();
      });

      expect(localStorage.getItem('jwt_token')).toBeNull();
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
    });

    it('GivenASignedInUser_WhenTheySignOut_ThenStorageIsClearedAndThePageNavigatesToLogin', async () => {
      // A full navigation rather than a router push, so the query cache goes with it and nothing
      // belonging to the previous user can be read by the next one.
      localStorage.setItem('jwt_token', 'stored.token');
      getMe.mockResolvedValue(USER);
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));

      await act(async () => {
        screen.getByText('sign out').click();
      });

      expect(localStorage.getItem('jwt_token')).toBeNull();
      expect(localStorage.getItem('user')).toBeNull();
      expect(window.location.href).toBe('/login');
    });
  });

  it('GivenNoProviderAbove_WhenTheAuthHookIsCalled_ThenItFailsLoudlyRatherThanReturningNull', () => {
    // The alternative is a null context surfacing much later as an unexplained property access deep
    // inside some component.
    expect(() => render(<Probe />)).toThrow(/AuthProvider/);
  });
});
