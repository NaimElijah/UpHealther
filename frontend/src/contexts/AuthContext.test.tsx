import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import { AuthProvider } from './AuthContext';
import { useAuth } from '../hooks/useAuth';
import { getAccessToken, clearAccessToken } from '../api/tokenStore';
import type { User } from '../types';

const getMe = vi.fn();
const loginApi = vi.fn();
const registerApi = vi.fn();
const logoutApi = vi.fn();
const renewSession = vi.fn();

vi.mock('../api/auth', () => ({
  getMe: (...args: unknown[]) => getMe(...args),
  loginApi: (...args: unknown[]) => loginApi(...args),
  registerApi: (...args: unknown[]) => registerApi(...args),
  logoutApi: (...args: unknown[]) => logoutApi(...args),
}));

vi.mock('../api/client', async () => {
  const tokenStore = await import('./../api/tokenStore');
  return {
    default: {},
    REQUESTED_WITH: 'X-Requested-With',
    // Stands in for the real one, which would reach the network. Setting the token on success is the
    // part the provider depends on: it does not set the token itself any more.
    renewSession: (...args: unknown[]) => {
      const result = renewSession(...args);
      return Promise.resolve(result).then((renewed) => {
        if (renewed) tokenStore.setAccessToken('renewed.jwt.token', '2099-01-01T00:00:00Z');
        return renewed;
      });
    },
  };
});

const USER: User = {
  id: 'user-1',
  name: 'Someone',
  email: 'someone@example.com',
  role: 'USER',
  createdAt: '2026-03-15T09:00:00',
};

const SESSION = { accessToken: 'issued.jwt.token', expiresAt: '2099-01-01T00:00:00Z', user: USER };

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
      {/* Swallowed the way Navbar does: it catches the rejection to tell the user it did not work. */}
      <button onClick={() => void logout().catch(() => {})}>sign out</button>
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
 * FR-3 — a session survives a reload — and the actions around it.
 *
 * The restore path is the one worth testing and the only one impossible to check by clicking, and it
 * changed shape entirely: there is no stored token to read any more. The access token lives in memory
 * and dies with the tab, so a page load starts signed out and asks the server, whose answer rests on
 * an `HttpOnly` cookie this code cannot see. That makes the assertions here about a round trip rather
 * than about `localStorage`, and it is why the "cached user shown before the server answered" case is
 * gone: there is no cache to show.
 *
 * What is emphatically still worth asserting is that nothing writes a credential to web storage, and
 * that a sign-out which failed says so rather than pretending.
 *
 * `window.location` is redefined because sign-out performs a full document navigation, which jsdom
 * refuses to do.
 */
describe('AuthProvider', () => {
  const realLocation = window.location;

  beforeEach(() => {
    getMe.mockReset();
    loginApi.mockReset();
    registerApi.mockReset();
    logoutApi.mockReset();
    renewSession.mockReset();
    renewSession.mockResolvedValue(false);
    clearAccessToken();
    localStorage.clear();
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
    it('GivenNoRefreshCookie_WhenTheProviderMounts_ThenItSettlesAnonymousWithoutFetchingAProfile', async () => {
      // The ordinary visitor. The refresh is refused, which is an answer and not an error.
      renewSession.mockResolvedValue(false);

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(getMe).not.toHaveBeenCalled();
    });

    it('GivenALiveRefreshCookie_WhenTheProviderMounts_ThenTheSessionIsRestored', async () => {
      renewSession.mockResolvedValue(true);
      getMe.mockResolvedValue(USER);

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));
      expect(screen.getByTestId('user').textContent).toBe(USER.email);
      expect(screen.getByTestId('loading').textContent).toBe('false');
    });

    it('GivenARenewedSessionWhoseProfileFails_WhenTheProviderMounts_ThenItSettlesAnonymous', async () => {
      // The account went away between the refresh and the profile read. Showing a signed-in shell with
      // no user behind it would be worse than showing the sign-in page.
      renewSession.mockResolvedValue(true);
      getMe.mockRejectedValue(new Error('gone'));

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
    });

    it('GivenLegacyCredentialsInStorage_WhenTheProviderMounts_ThenTheyAreRemoved', async () => {
      // Left over from when the token lived in localStorage. A credential nobody reads is still a
      // credential sitting where injected script can find it, and it would sit there for years.
      localStorage.setItem('jwt_token', 'an.old.token');
      localStorage.setItem('user', JSON.stringify(USER));

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(localStorage.getItem('jwt_token')).toBeNull();
      expect(localStorage.getItem('user')).toBeNull();
    });
  });

  describe('signing in and out', () => {
    it('GivenValidCredentials_WhenTheUserSignsIn_ThenTheSessionStartsAndNothingReachesWebStorage', async () => {
      loginApi.mockResolvedValue(SESSION);
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));

      await act(async () => {
        screen.getByText('sign in').click();
      });

      expect(screen.getByTestId('authenticated').textContent).toBe('true');
      expect(screen.getByTestId('user').textContent).toBe(USER.email);
      expect(getAccessToken()).toBe('issued.jwt.token');
      expect(localStorage.length).toBe(0);
    });

    it('GivenCredentialsTheServerRejects_WhenTheUserSignsIn_ThenNothingIsStarted', async () => {
      loginApi.mockRejectedValue(new Error('bad credentials'));
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));

      await act(async () => {
        screen.getByText('sign in').click();
      });

      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(getAccessToken()).toBeNull();
    });

    it('GivenASignedInUser_WhenTheySignOut_ThenTheServerIsToldAndThePageNavigatesToLogin', async () => {
      loginApi.mockResolvedValue(SESSION);
      logoutApi.mockResolvedValue(undefined);
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      await act(async () => {
        screen.getByText('sign in').click();
      });

      await act(async () => {
        screen.getByText('sign out').click();
      });

      expect(logoutApi).toHaveBeenCalled();
      expect(getAccessToken()).toBeNull();
      expect(window.location.href).toBe('/login');
    });

    it('GivenTheServerCannotBeReached_WhenTheUserSignsOut_ThenTheyStaySignedInRatherThanBeingToldOtherwise', async () => {
      // The honest state. The refresh cookie is still in the browser, so the session is still live —
      // clearing the UI would tell somebody on a shared machine they had signed out when they had not.
      loginApi.mockResolvedValue(SESSION);
      logoutApi.mockRejectedValue(new Error('network'));
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      await act(async () => {
        screen.getByText('sign in').click();
      });

      await act(async () => {
        screen.getByText('sign out').click();
      });

      expect(screen.getByTestId('authenticated').textContent).toBe('true');
      expect(getAccessToken()).toBe('issued.jwt.token');
      expect(window.location.href).toBe('');
    });
  });
});
