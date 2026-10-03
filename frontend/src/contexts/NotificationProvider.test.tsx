import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
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
const getUnreadCount = vi.fn();
const markNotificationRead = vi.fn();
const markAllNotificationsRead = vi.fn();
vi.mock('../api/notifications', () => ({
  getNotifications: () => getNotifications(),
  getUnreadCount: () => getUnreadCount(),
  markNotificationRead: (id: string) => markNotificationRead(id),
  markAllNotificationsRead: () => markAllNotificationsRead(),
}));

/** A count request the server has not answered yet, and in the test never will. */
const unanswered = () => new Promise<number>(() => {});

/**
 * Lets every promise chain already started run to its end. Nothing here does real I/O, so by the next
 * macrotask each one has; `act` alone drains too few microtasks for a write queued behind a cancel.
 */
const settled = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** Puts every notification-API double back to a server with nothing in it that answers every call. */
function resetNotificationApi() {
  for (const fn of [getNotifications, getUnreadCount, markNotificationRead, markAllNotificationsRead]) fn.mockReset();
  getNotifications.mockResolvedValue([]);
  getUnreadCount.mockResolvedValue(0);
  markNotificationRead.mockResolvedValue(undefined);
  markAllNotificationsRead.mockResolvedValue(undefined);
}

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
  const { notifications, unreadCount, connected, desktopPermission, markRead, markAllRead, requestDesktopPermission } =
    useNotifications();
  return (
    <div>
      <span data-testid="count">{notifications.length}</span>
      <span data-testid="unread">{unreadCount}</span>
      <span data-testid="connected">{String(connected)}</span>
      <span data-testid="desktop">{desktopPermission}</span>
      <button onClick={requestDesktopPermission}>Enable desktop alerts</button>
      <button onClick={() => markRead(PUSHED.id)}>Mark read</button>
      <button onClick={markAllRead}>Mark all read</button>
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
    resetNotificationApi();
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

  // FR-33 (#94) — the unread count covers every notification, and the list holds only the fifty most
  // recent. A count taken from the list can never pass fifty, and undercounts as soon as an unread one
  // falls off the end of it.

  it('GivenMoreUnreadThanTheFiftyListed_WhenTheBadgeIsRead_ThenItShowsTheServersCount', async () => {
    getNotifications.mockResolvedValue(Array.from({ length: 50 }, (_, i) => ({ ...PUSHED, id: `n-${i}` })));
    getUnreadCount.mockResolvedValue(73);

    renderProvider();

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('50'));
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));
  });

  it('GivenTheCountCannotBeRead_WhenTheBadgeIsRead_ThenItFallsBackToTheUnreadListed', async () => {
    // Read as zero, a failed count hid the badge and every "Mark all read" while unread rows showed.
    // The unread listed is a lower bound, and what the badge said before the count was the server's.
    getNotifications.mockResolvedValue([PUSHED, { ...PUSHED, id: 'n-2' }, { ...PUSHED, id: 'n-3', read: true }]);
    getUnreadCount.mockRejectedValue(new Error('Network Error'));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('3'));
    await waitFor(() => expect(getUnreadCount).toHaveBeenCalled());
    await act(settled);

    expect(screen.getByTestId('unread').textContent).toBe('2');
  });

  it('GivenTheCountCannotBeRead_WhenANotificationIsMarkedRead_ThenTheBadgeStillCountsTheListed', async () => {
    // A drop applied to a count never read would invent a zero and hide the controls again.
    getNotifications.mockResolvedValue([PUSHED, { ...PUSHED, id: 'n-2' }]);
    getUnreadCount.mockRejectedValue(new Error('Network Error'));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('2'));

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());

    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledWith(PUSHED.id));
    await act(settled);
    expect(screen.getByTestId('unread').textContent).toBe('1');
  });

  it('GivenALiveNotification_WhenItArrives_ThenTheCountIsReadAgainFromTheServer', async () => {
    // Counting it locally instead could count it twice: the server's count may already include a
    // notification whose push arrives after the count was fetched.
    getUnreadCount.mockResolvedValueOnce(0).mockResolvedValue(1);
    renderProvider();
    await waitFor(() => expect(getUnreadCount).toHaveBeenCalledTimes(1));
    act(() => lastClient?.connect());

    act(() => lastClient?.deliver(JSON.stringify(PUSHED)));

    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('1'));
    expect(getUnreadCount).toHaveBeenCalledTimes(2);
  });

  // The three below hold the count's re-read after the write unanswered, so what they see is the
  // optimistic write alone.

  it('GivenAnUnreadNotification_WhenItIsMarkedRead_ThenTheCountDropsByOne', async () => {
    getNotifications.mockResolvedValue([PUSHED]);
    getUnreadCount.mockResolvedValueOnce(73).mockImplementation(unanswered);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());

    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('72'));
    expect(markNotificationRead).toHaveBeenCalledWith(PUSHED.id);
  });

  it('GivenANotificationAlreadyRead_WhenItIsMarkedReadAgain_ThenTheCountIsUnchanged', async () => {
    getNotifications.mockResolvedValue([{ ...PUSHED, read: true }]);
    getUnreadCount.mockResolvedValueOnce(5).mockImplementation(unanswered);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('5'));

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());

    await waitFor(() => expect(markNotificationRead).toHaveBeenCalled());
    expect(screen.getByTestId('unread').textContent).toBe('5');
  });

  it('GivenUnreadNotifications_WhenAllAreMarkedRead_ThenTheCountIsZero', async () => {
    // Zero is right even past the fifty listed: the server marks every notification read, not only
    // the ones this list holds.
    getNotifications.mockResolvedValue([PUSHED]);
    getUnreadCount.mockResolvedValueOnce(73).mockImplementation(unanswered);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));

    act(() => screen.getByRole('button', { name: 'Mark all read' }).click());

    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('0'));
    expect(markAllNotificationsRead).toHaveBeenCalledTimes(1);
  });

  it('GivenACountFetchInFlight_WhenANotificationIsMarkedRead_ThenTheStaleAnswerDoesNotStick', async () => {
    // A push starts a count fetch, and the server answers it before the mark-read reaches it. Landing
    // on top of the optimistic write, that answer would put the notification back in the count.
    getNotifications.mockResolvedValue([PUSHED]);
    let staleAnswer: (count: number) => void = () => {};
    getUnreadCount
      .mockResolvedValueOnce(73)
      .mockImplementationOnce(() => new Promise<number>((resolve) => { staleAnswer = resolve; }))
      .mockResolvedValue(72);
    let confirmRead: () => void = () => {};
    markNotificationRead.mockImplementation(() => new Promise<void>((resolve) => { confirmRead = resolve; }));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));
    act(() => lastClient?.connect());
    act(() => lastClient?.deliver(JSON.stringify({ ...PUSHED, id: 'n-2' })));
    await waitFor(() => expect(getUnreadCount).toHaveBeenCalledTimes(2));

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());
    await act(async () => staleAnswer(73));
    await act(settled);

    // The stale answer lands while the write is still in flight: only cancelling the fetch keeps it out.
    expect(screen.getByTestId('unread').textContent).toBe('72');

    await act(async () => confirmRead());

    await waitFor(() => expect(getUnreadCount).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('72'));
  });

  it('GivenTheCountAlreadyExcludesTheNotification_WhenItIsMarkedRead_ThenTheServersCountWins', async () => {
    // The list can be older than the count, since a push refreshes the count alone. Read in another
    // tab, the notification is out of the count while this list still says unread, so the optimistic
    // drop is one too many until the count is read again.
    getNotifications.mockResolvedValue([PUSHED]);
    getUnreadCount.mockResolvedValue(5);
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('5'));

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());

    await waitFor(() => expect(getUnreadCount).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('5'));
  });

  it('GivenTheServerRefusesAReadAll_WhenItFails_ThenTheCountIsReadAgain', async () => {
    // The optimistic zero would otherwise outlive a refusal, and hide the controls until the next push.
    getNotifications.mockResolvedValue([PUSHED]);
    getUnreadCount.mockResolvedValue(73);
    markAllNotificationsRead.mockRejectedValue(new Error('Network Error'));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));

    act(() => screen.getByRole('button', { name: 'Mark all read' }).click());

    await waitFor(() => expect(getUnreadCount).toHaveBeenCalledTimes(2));
    await act(settled);
    expect(screen.getByTestId('unread').textContent).toBe('73');
  });

  it('GivenTheServerRefusesAMarkRead_WhenItFails_ThenTheCountIsReadAgain', async () => {
    getNotifications.mockResolvedValue([PUSHED]);
    getUnreadCount.mockResolvedValue(73);
    markNotificationRead.mockRejectedValue(new Error('Network Error'));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));
    const fetchesBefore = getUnreadCount.mock.calls.length;

    act(() => screen.getByRole('button', { name: 'Mark read' }).click());

    await waitFor(() => expect(getUnreadCount.mock.calls.length).toBeGreaterThan(fetchesBefore));
    await waitFor(() => expect(screen.getByTestId('unread').textContent).toBe('73'));
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

/** Records the desktop notifications the provider raises, in place of the browser's own. */
class FakeDesktopNotification {
  static permission: NotificationPermission = 'default';
  static raised: Array<{ title: string; body?: string }> = [];
  /** The user says yes: the path FR-49's opt-in exists for. */
  static requestPermission = () => Promise.resolve<NotificationPermission>('granted');
  onclick: (() => void) | null = null;

  constructor(title: string, options?: { body?: string }) {
    FakeDesktopNotification.raised.push({ title, body: options?.body });
  }

  close() {}
}

function setTabHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
}

/**
 * FR-49: desktop notifications are opt-in, and raised only while the tab is in the background.
 *
 * The background condition is the half worth pinning. With the tab in view the toast already says it,
 * so raising both would notify the user twice for one event.
 */
describe('NotificationProvider desktop notifications', () => {
  beforeEach(() => {
    lastClient = undefined;
    resetNotificationApi();
    FakeDesktopNotification.permission = 'default';
    FakeDesktopNotification.raised = [];
    vi.stubGlobal('Notification', FakeDesktopNotification);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    // Removes the own property setTabHidden defined, so jsdom's own getter shows through again.
    Reflect.deleteProperty(document, 'hidden');
  });

  async function deliverWhileConnected(notification: AppNotification) {
    renderProvider();
    await waitFor(() => expect(lastClient).toBeDefined());
    act(() => lastClient?.connect());
    act(() => lastClient?.deliver(JSON.stringify(notification)));
  }

  it('GivenPermissionNotYetAsked_WhenTheUserOptsIn_ThenTheGrantedPermissionIsRecorded', async () => {
    renderProvider();
    expect(screen.getByTestId('desktop').textContent).toBe('default');

    await act(async () => {
      screen.getByRole('button', { name: 'Enable desktop alerts' }).click();
    });

    expect(screen.getByTestId('desktop').textContent).toBe('granted');
  });

  it('GivenAHiddenTabAndGrantedPermission_WhenANotificationArrives_ThenADesktopNotificationIsRaised', async () => {
    FakeDesktopNotification.permission = 'granted';
    setTabHidden(true);

    await deliverWhileConnected(PUSHED);

    expect(FakeDesktopNotification.raised).toEqual([{ title: 'Upgrade completed', body: 'Congrats!' }]);
  });

  it('GivenTheTabIsInView_WhenANotificationArrives_ThenItIsListedButNoDesktopNotificationIsRaised', async () => {
    FakeDesktopNotification.permission = 'granted';
    setTabHidden(false);

    await deliverWhileConnected(PUSHED);

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('1'));
    expect(FakeDesktopNotification.raised).toEqual([]);
  });

  it('GivenPermissionWasNeverGranted_WhenANotificationArrivesInAHiddenTab_ThenNoDesktopNotificationIsRaised', async () => {
    FakeDesktopNotification.permission = 'default';
    setTabHidden(true);

    await deliverWhileConnected(PUSHED);

    await waitFor(() => expect(screen.getByTestId('count').textContent).toBe('1'));
    expect(FakeDesktopNotification.raised).toEqual([]);
  });
});
