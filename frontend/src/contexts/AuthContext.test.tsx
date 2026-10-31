import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './AuthContext';
import { NotificationProvider } from './NotificationProvider';
import { ThemeProvider } from './ThemeProvider';
import { useAuth } from '../hooks/useAuth';
import { useNotifications } from '../hooks/useNotifications';
import { createQueryClient } from '../lib/queryClient';
import LoginPage from '../pages/LoginPage';
import RegisterPage from '../pages/RegisterPage';
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

/** The access token each notification-list read and each socket connection went out with, in order. */
const listedWith: (string | null)[] = [];
const socketOpenedWith: (string | null)[] = [];

vi.mock('../api/notifications', async () => {
  const tokenStore = await import('./../api/tokenStore');
  return {
    getNotifications: async () => {
      listedWith.push(tokenStore.getAccessToken());
      return [];
    },
    getUnreadCount: async () => 0,
    markNotificationRead: async () => {},
    markAllNotificationsRead: async () => {},
  };
});

// The real socket reads the token as it connects, after activate() returns, and the CONNECT frame
// carries what it read for the life of the connection.
vi.mock('../api/notificationSocket', async () => {
  const tokenStore = await import('./../api/tokenStore');
  return {
    openNotificationSocket: () => {
      queueMicrotask(() => socketOpenedWith.push(tokenStore.getAccessToken()));
      return () => {};
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
  const { user, isAuthenticated, isLoading, login, register, logout } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(isLoading)}</span>
      <span data-testid="authenticated">{String(isAuthenticated)}</span>
      <span data-testid="user">{user?.email ?? 'none'}</span>
      {/* Swallowed the way LoginPage does: it catches the rejection to show an error in the form. */}
      <button onClick={() => void login('someone@example.com', 's3cret!').catch(() => {})}>sign in</button>
      {/* Swallowed the way Navbar does: it catches the rejection to tell the user it did not work. */}
      <button onClick={() => void logout().catch(() => {})}>sign out</button>
      <button onClick={() => void register('Someone', 'someone@example.com', 's3cret!').catch(() => {})}>
        register
      </button>
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
    listedWith.length = 0;
    socketOpenedWith.length = 0;
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

    it('GivenTheRestoreIsRateLimited_WhenTheProviderMounts_ThenItSettlesAnonymousAndEndsNoSession', async () => {
      // A 429 rejects the renewal rather than answering false: the address spent its allowance, perhaps
      // somebody else's doing, and the cookie is still good. This tab shows nobody signed in, and the
      // next page load, once the window turns, restores the session; ending it here would not.
      renewSession.mockRejectedValue(Object.assign(new Error('Too many requests'), { response: { status: 429 } }));

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(getMe).not.toHaveBeenCalled();
      expect(window.location.href).toBe('');
    });

    it('GivenARenewedSessionWhoseProfileFails_WhenTheProviderMounts_ThenItSettlesAnonymous', async () => {
      // The account went away between the refresh and the profile read. Showing a signed-in shell with
      // no user behind it would be worse than showing the sign-in page.
      renewSession.mockResolvedValue(true);
      getMe.mockRejectedValue(new Error('gone'));

      renderProvider();

      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      // The renewal installed a token; left in memory, every later request would carry a session the
      // page has just declared over.
      expect(getAccessToken()).toBeNull();
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

  // A browser can hold one account's live refresh cookie while somebody else signs in, before the
  // page-load restore has answered. Landing late, the restore replaced the new token and user with the
  // previous account's, so the second person wrote into the first one's account without noticing.
  describe('signing in while the page-load restore is in flight', () => {
    const PREVIOUS: User = { ...USER, id: 'user-0', email: 'previous@example.com' };

    /** Holds the restore open until the test answers it, the previous account's cookie behind it. */
    function holdTheRestore(): (renewed: boolean) => Promise<void> {
      let answer: (renewed: boolean) => void = () => {};
      renewSession.mockReturnValue(new Promise<boolean>((resolve) => (answer = resolve)));
      getMe.mockResolvedValue(PREVIOUS);
      return async (renewed) => {
        await act(async () => answer(renewed));
        await act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));
      };
    }

    it('GivenARestoreInFlight_WhenAnotherAccountSignsIn_ThenTheLateRestoreDoesNotReplaceIt', async () => {
      const answerRestore = holdTheRestore();
      loginApi.mockResolvedValue(SESSION);
      renderProvider();

      await act(async () => {
        screen.getByText('sign in').click();
      });
      await answerRestore(true);

      await waitFor(() => expect(screen.getByTestId('user').textContent).toBe(USER.email));
      expect(getAccessToken()).toBe('issued.jwt.token');
    });

    it('GivenARestoreInFlight_WhenAnAccountIsRegistered_ThenTheLateRestoreDoesNotReplaceIt', async () => {
      const answerRestore = holdTheRestore();
      registerApi.mockResolvedValue(SESSION);
      renderProvider();

      await act(async () => {
        screen.getByText('register').click();
      });
      await answerRestore(true);

      await waitFor(() => expect(screen.getByTestId('user').textContent).toBe(USER.email));
      expect(getAccessToken()).toBe('issued.jwt.token');
    });

    it('GivenARestoreInFlightThatFails_WhenTheUserSignsIn_ThenTheSignInStillStands', async () => {
      // A failed restore clears the token it may have installed; landing late, it cleared the new one.
      const answerRestore = holdTheRestore();
      getMe.mockRejectedValue(new Error('gone'));
      loginApi.mockResolvedValue(SESSION);
      renderProvider();

      await act(async () => {
        screen.getByText('sign in').click();
      });
      await answerRestore(true);

      await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));
      expect(getAccessToken()).toBe('issued.jwt.token');
    });

    // Waiting inside login() was not enough on its own. A restore that succeeds signs the tab in and
    // redirects it before the waiting sign-in goes out, and the socket opens on the restored token; the
    // sign-in then installed another account's token under it, or, refused, said nothing on a page that
    // had already gone. So the forms refuse to submit until the restore has settled.
    describe('through the sign-in and registration forms', () => {
      /** Stands in for any page the redirect mounts, showing who the tab says is signed in. */
      function SignedInPage() {
        const { user } = useAuth();
        useNotifications();
        return <span data-testid="signed-in-as">{user?.email}</span>;
      }

      function renderForms(path: '/login' | '/register') {
        return render(
          <ThemeProvider>
            <QueryClientProvider client={createQueryClient()}>
              <AuthProvider>
                <MemoryRouter initialEntries={[path]}>
                  <NotificationProvider>
                    <Routes>
                      <Route path="/login" element={<LoginPage />} />
                      <Route path="/register" element={<RegisterPage />} />
                      <Route path="/dashboard" element={<SignedInPage />} />
                    </Routes>
                  </NotificationProvider>
                </MemoryRouter>
              </AuthProvider>
            </QueryClientProvider>
          </ThemeProvider>,
        );
      }

      function fillSignIn(email: string, password: string) {
        fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
        fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
      }

      /** Fires the form's submit directly, so the handler's own refusal is tested, not only the refusing button. */
      async function submit(buttonName: string) {
        await act(async () => {
          fireEvent.submit(screen.getByRole('button', { name: buttonName }));
        });
      }

      function refusedAs(status: number): AxiosError {
        const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
        const response = { data: {}, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
        return new AxiosError('Request failed', String(status), config, {}, response);
      }

      it('GivenARestoreInFlight_WhenTheSignInFormIsSubmitted_ThenNothingIsSentAndTheTabIsTheRestoredAccountThroughout', async () => {
        const answerRestore = holdTheRestore();
        loginApi.mockResolvedValue(SESSION);
        renderForms('/login');

        expect(screen.getByRole('button', { name: 'Sign In' }).getAttribute('aria-disabled')).toBe('true');
        fillSignIn(USER.email, 's3cret!');
        await submit('Sign In');
        await answerRestore(true);

        await waitFor(() => expect(screen.getByTestId('signed-in-as').textContent).toBe(PREVIOUS.email));
        await waitFor(() => expect(socketOpenedWith).toHaveLength(1));
        await waitFor(() => expect(listedWith).toHaveLength(1));
        expect(loginApi).not.toHaveBeenCalled();
        expect(getAccessToken()).toBe('renewed.jwt.token');
        expect(socketOpenedWith).toEqual(['renewed.jwt.token']);
        expect(listedWith).toEqual(['renewed.jwt.token']);
      });

      it('GivenARestoreInFlight_WhenTheRegistrationFormIsSubmitted_ThenNothingIsSent', async () => {
        const answerRestore = holdTheRestore();
        registerApi.mockResolvedValue(SESSION);
        renderForms('/register');

        expect(screen.getByRole('button', { name: 'Create Account' }).getAttribute('aria-disabled')).toBe('true');
        fireEvent.change(screen.getByLabelText('Full Name'), { target: { value: USER.name } });
        fillSignIn(USER.email, 's3cret!-long');
        fireEvent.change(screen.getByLabelText('Confirm Password'), { target: { value: 's3cret!-long' } });
        await submit('Create Account');
        await answerRestore(true);

        await waitFor(() => expect(screen.getByTestId('signed-in-as').textContent).toBe(PREVIOUS.email));
        expect(registerApi).not.toHaveBeenCalled();
        expect(getAccessToken()).toBe('renewed.jwt.token');
      });

      it('GivenASubmitDuringTheRestore_WhenTheRestoreFindsNoSessionAndTheSignInIsThenRefused_ThenTheRefusalIsShown', async () => {
        const answerRestore = holdTheRestore();
        loginApi.mockRejectedValue(refusedAs(401));
        renderForms('/login');

        fillSignIn(USER.email, 'mistyped-1');
        await submit('Sign In');
        await answerRestore(false);
        expect(loginApi).not.toHaveBeenCalled();
        await submit('Sign In');

        expect(await screen.findByText('Invalid email or password.')).toBeDefined();
        expect(loginApi).toHaveBeenCalledTimes(1);
        expect(getAccessToken()).toBeNull();
      });
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

    it('GivenASignedInUser_WhenTheySignOut_ThenTheAppRendersSignedOutUntilThePageUnloads', async () => {
      // A document navigation is not instant, and the app goes on rendering until the page unloads. In
      // those moments it renders nobody signed in, so no protected page and no socket outlives the sign-out.
      loginApi.mockResolvedValue(SESSION);
      logoutApi.mockResolvedValue(undefined);
      renderProvider();
      await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'));
      await act(async () => {
        screen.getByText('sign in').click();
      });
      // Signed in first, so the signed-out state below is the sign-out's, not a sign-in that failed.
      expect(screen.getByTestId('authenticated').textContent).toBe('true');

      await act(async () => {
        screen.getByText('sign out').click();
      });

      expect(screen.getByTestId('authenticated').textContent).toBe('false');
      expect(screen.getByTestId('user').textContent).toBe('none');
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
