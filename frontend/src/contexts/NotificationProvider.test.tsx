import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { NotificationProvider } from './NotificationProvider';
import { AuthContext, type AuthContextType } from './authContextValue';
import { useNotifications } from '../hooks/useNotifications';
import { clearAccessToken, setAccessToken } from '../api/tokenStore';

const renewSession = vi.fn();
vi.mock('../api/client', async () => {
  const tokenStore = await import('./../api/tokenStore');
  return {
    default: {},
    REQUESTED_WITH: 'X-Requested-With',
    // Stands in for the real renewal: on success it installs a fresh token, which is what the
    // socket's beforeConnect hook then reads.
    renewSession: () => Promise.resolve(renewSession()).then((ok) => {
      if (ok) tokenStore.setAccessToken('renewed.jwt.token', '2099-01-01T00:00:00Z');
      return ok;
    }),
  };
});

import type { AppNotification } from '../types';

/** The last Client the provider constructed, so a test can drive its callbacks. */
let lastClient: FakeClient | undefined;

type StompHandler = (message: { body: string }) => void;

/** Stands in for `@stomp/stompjs`'s Client, recording the lifecycle calls the provider makes. */
class FakeClient {
  activated = 0;
  deactivated = 0;
  subscriptions: string[] = [];
  /** Written by the provider's beforeConnect hook, and read by the real client on every attempt. */
  connectHeaders: Record<string, string> = {};
  private handler?: StompHandler;
  private readonly config: { onConnect?: () => void; beforeConnect?: () => unknown };

  constructor(config: { onConnect?: () => void; beforeConnect?: () => unknown }) {
    this.config = config;
  }

  /**
   * Simulates the client preparing a connection attempt, which is when the token is read.
   *
   * Returns whatever the hook returns so a test can await it. stompjs 7 awaits this hook, and the
   * provider's is async because it may have to renew a lapsed token first — swallowing the promise
   * here would leave a test asserting against headers that had not been written yet.
   */
  prepareConnect(): unknown {
    return this.config.beforeConnect?.();
  }

  activate() {
    this.activated += 1;
  }

  deactivate() {
    this.deactivated += 1;
    return Promise.resolve();
  }

  subscribe(destination: string, handler: StompHandler) {
    this.subscriptions.push(destination);
    this.handler = handler;
    return { unsubscribe: () => {} };
  }

  /** Simulates the broker connecting, which is what makes the provider subscribe. */
  connect() {
    this.config.onConnect?.();
  }

  /** Simulates a frame arriving on the subscribed destination. */
  deliver(body: string) {
    this.handler?.({ body });
  }
}

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    constructor(config: { onConnect?: () => void; beforeConnect?: () => unknown }) {
      // Recorded here rather than in FakeClient's own constructor: aliasing `this` out of a
      // constructor is what `no-this-alias` exists to stop, and the seam belongs to the mock anyway.
      const client = new FakeClient(config);
      lastClient = client;
      return client as unknown as object;
    }
  },
}));

const getNotifications = vi.fn();
vi.mock('../api/notifications', () => ({
  getNotifications: () => getNotifications(),
  markNotificationRead: vi.fn(() => Promise.resolve()),
  markAllNotificationsRead: vi.fn(() => Promise.resolve()),
}));

const PUSHED: AppNotification = {
  id: 'n-1',
  type: 'UPGRADE_COMPLETED',
  category: 'SUCCESS',
  title: 'Upgrade completed',
  message: 'Congrats!',
  relatedUpgradeId: undefined,
  read: false,
  createdAt: '2026-03-15T09:00:00',
};

function Probe() {
  const { notifications, unreadCount, connected } = useNotifications();
  return (
    <div>
      <span data-testid="count">{notifications.length}</span>
      <span data-testid="unread">{unreadCount}</span>
      <span data-testid="connected">{String(connected)}</span>
    </div>
  );
}

function authenticated(): AuthContextType {
  return {
    user: null,
    isLoading: false,
    isAuthenticated: true,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
  };
}

function renderProvider(auth: AuthContextType = authenticated()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <NotificationProvider>
            <Probe />
          </NotificationProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </AuthContext.Provider>,
  );
}

/**
 * FR-32's client half: a notification raised while the tab is open arrives live, and the same one is
 * readable afterwards however it got there.
 *
 * Three things here are only observable from a test. The socket is opened from an effect whose cleanup
 * must deactivate it — a dropped cleanup leaks a connection per mount, and `React.StrictMode`
 * double-invokes effects, so the leak starts in development rather than in production. A frame that
 * will not parse must be dropped rather than thrown, because an exception inside a STOMP callback tears
 * down the subscription and costs every *later* notification too. And an arrival must be de-duplicated
 * by id against the REST fetch, since a notification can genuinely come both ways.
 */
describe('NotificationProvider', () => {
  beforeEach(() => {
    lastClient = undefined;
    renewSession.mockReset();
    getNotifications.mockReset();
    getNotifications.mockResolvedValue([]);
  });

  it('GivenASignedInUser_WhenTheProviderMounts_ThenItOpensASocketAndSubscribesOnConnect', async () => {
    renderProvider();
    await waitFor(() => expect(lastClient).toBeDefined());

    expect(lastClient?.activated).toBe(1);

    act(() => lastClient?.connect());

    expect(lastClient?.subscriptions).toEqual(['/user/queue/notifications']);
    expect(screen.getByTestId('connected').textContent).toBe('true');
  });

  it('GivenAnAnonymousCaller_WhenTheProviderMounts_ThenNoSocketIsOpened', async () => {
    // The socket authenticates with the token, so opening one without a session can only fail — and it
    // would retry every five seconds for as long as the login page is open.
    renderProvider({ ...authenticated(), isAuthenticated: false });

    await waitFor(() => expect(screen.getByTestId('connected').textContent).toBe('false'));
    expect(lastClient).toBeUndefined();
  });

  it('GivenAnOpenSocket_WhenTheProviderUnmounts_ThenTheConnectionIsClosed', async () => {
    const { unmount } = renderProvider();
    await waitFor(() => expect(lastClient).toBeDefined());
    const client = lastClient;

    unmount();

    expect(client?.deactivated).toBe(1);
  });

  it('GivenANotificationArrivesOverTheSocket_WhenItIsHandled_ThenItAppearsInTheList', async () => {
    renderProvider();
    await waitFor(() => expect(lastClient).toBeDefined());
    act(() => lastClient?.connect());

    act(() => lastClient?.deliver(JSON.stringify(PUSHED)));

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('1'));
    expect(screen.getByTestId('unread').textContent).toBe('1');
  });

  it('GivenANotificationAlreadyFetched_WhenTheSameOneArrivesLive_ThenItIsNotListedTwice', async () => {
    getNotifications.mockResolvedValue([PUSHED]);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('1'));
    act(() => lastClient?.connect());

    act(() => lastClient?.deliver(JSON.stringify(PUSHED)));

    expect(screen.getByTestId('count').textContent).toBe('1');
  });

  it('GivenAFrameThatWillNotParse_WhenItArrives_ThenItIsDroppedAndLaterOnesStillArrive', async () => {
    // The reason the handler catches: throwing here propagates into the STOMP client and tears down
    // the subscription, so the cost is every notification after this one, not just this one.
    renderProvider();
    await waitFor(() => expect(lastClient).toBeDefined());
    act(() => lastClient?.connect());

    act(() => lastClient?.deliver('not json'));
    act(() => lastClient?.deliver(JSON.stringify(PUSHED)));

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('1'));
  });

  it('GivenReadAndUnreadNotifications_WhenTheBadgeIsRead_ThenOnlyTheUnreadOnesAreCounted', async () => {
    getNotifications.mockResolvedValue([PUSHED, { ...PUSHED, id: 'n-2', read: true }]);

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('2'));
    expect(screen.getByTestId('unread').textContent).toBe('1');
  });

  it('GivenTheTokenHasLapsed_WhenTheSocketReconnects_ThenItIsRenewedBeforeTheAttempt', async () => {
    // Without this the socket retries a dead token every five seconds forever: live notifications
    // silently stop and the server logs a refused CONNECT on a loop. A tab idle past the token's
    // fifteen minutes, with no HTTP traffic to renew it, is all it takes.
    setAccessToken('expired.jwt.token', '2000-01-01T00:00:00Z');
    renewSession.mockReturnValue(true);
    renderProvider(authenticated());
    await waitFor(() => expect(lastClient).toBeDefined());

    await act(async () => {
      await lastClient?.prepareConnect();
    });

    expect(renewSession).toHaveBeenCalled();
    expect(lastClient?.connectHeaders).toEqual({ Authorization: 'Bearer renewed.jwt.token' });
  });

  it('GivenAUsableToken_WhenTheSocketReconnects_ThenNothingIsRenewedNeedlessly', async () => {
    setAccessToken('still.good.token', '2099-01-01T00:00:00Z');
    renewSession.mockReturnValue(true);
    renderProvider(authenticated());
    await waitFor(() => expect(lastClient).toBeDefined());

    await act(async () => {
      await lastClient?.prepareConnect();
    });

    expect(renewSession).not.toHaveBeenCalled();
    expect(lastClient?.connectHeaders).toEqual({ Authorization: 'Bearer still.good.token' });
  });
  it('GivenTheTokenWasRenewed_WhenTheSocketReconnects_ThenItAuthenticatesWithTheCurrentOne', async () => {
    // The access token now lasts minutes and is replaced underneath this component. A header
    // captured when the effect ran would be stale by the first reconnect, and putting the token in
    // the dependency list instead would tear the socket down every time it was renewed.
    setAccessToken('first.jwt.token', '2099-01-01T00:00:00Z');
    renderProvider(authenticated());
    await waitFor(() => expect(lastClient).toBeDefined());

    await act(async () => {
      await lastClient?.prepareConnect();
    });
    expect(lastClient?.connectHeaders).toEqual({ Authorization: 'Bearer first.jwt.token' });

    setAccessToken('renewed.jwt.token', '2099-01-01T00:00:00Z');
    await act(async () => {
      await lastClient?.prepareConnect();
    });

    expect(lastClient?.connectHeaders).toEqual({ Authorization: 'Bearer renewed.jwt.token' });
    expect(lastClient?.deactivated).toBe(0);
  });

  it('GivenNoTokenYet_WhenTheSocketPreparesToConnect_ThenItSendsNoAuthorizationHeader', async () => {
    // Mid-renewal there is briefly no token. Sending `Bearer null` would be refused just the same,
    // but sending nothing is honest and the refusal schedules an ordinary reconnect.
    clearAccessToken();
    renderProvider(authenticated());
    await waitFor(() => expect(lastClient).toBeDefined());

    await act(async () => {
      await lastClient?.prepareConnect();
    });

    expect(lastClient?.connectHeaders).toEqual({});
  });
});
