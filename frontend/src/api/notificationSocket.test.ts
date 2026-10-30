import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { openNotificationSocket, type NotificationSocketHandlers } from './notificationSocket';
import { clearAccessToken, getAccessToken, setAccessToken } from './tokenStore';
import { notificationAsSent } from '../test/wireFixtures';
import type { AppNotification } from '../types';

/** The last client the socket constructed, so a test can read its configuration and drive it. */
let lastClient: FakeClient | undefined;

/** The part of stompjs's client configuration these tests read or drive. */
type FakeClientConfig = {
  brokerURL?: string;
  onConnect?: () => void;
  onWebSocketClose?: () => void;
  onStompError?: (frame: { headers: Record<string, string> }) => void;
};

/** Stands in for `@stomp/stompjs`'s Client, recording what the socket asks of it. */
class FakeClient {
  activated = 0;
  deactivated = 0;
  subscriptions: string[] = [];
  readonly config: FakeClientConfig;
  private handler?: (message: { body: string }) => void;

  constructor(config: FakeClientConfig) {
    this.config = config;
  }

  activate() {
    this.activated += 1;
  }

  deactivate() {
    this.deactivated += 1;
    return Promise.resolve();
  }

  subscribe(destination: string, handler: (message: { body: string }) => void) {
    this.subscriptions.push(destination);
    this.handler = handler;
    return { unsubscribe: () => {} };
  }

  /** Simulates a frame arriving on the subscribed destination. */
  deliver(body: string) {
    this.handler?.({ body });
  }
}

// Faked at the library's boundary, as `NotificationProvider.test.tsx` does and for the reason it gives:
// there is no container for a browser socket client. The real one is driven by the Playwright smoke suite.
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    constructor(config: FakeClientConfig) {
      const client = new FakeClient(config);
      lastClient = client;
      return client as unknown as object;
    }
  },
}));

const realLocation = window.location;

/** Puts the page on a chosen origin; the socket reads only the protocol and the host. */
function pageAt(protocol: 'http:' | 'https:', host: string) {
  Object.defineProperty(window, 'location', { configurable: true, writable: true, value: { protocol, host } });
}

/** Opens the socket with recording handlers, and returns them with the client it built. */
function open() {
  const handlers = {
    onNotification: vi.fn<NotificationSocketHandlers['onNotification']>(),
    onConnectionChange: vi.fn<NotificationSocketHandlers['onConnectionChange']>(),
  };
  const close = openNotificationSocket(handlers);
  if (!lastClient) throw new Error('openNotificationSocket constructed no client');
  return { ...handlers, close, client: lastClient };
}

/** Opens the socket and has the broker accept it, which is what makes it subscribe. */
function openConnected() {
  const opened = open();
  opened.client.config.onConnect?.();
  return opened;
}

describe('openNotificationSocket', () => {
  beforeEach(() => {
    lastClient = undefined;
    clearAccessToken();
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  describe('where it connects', () => {
    it('GivenNoApiUrlOnAnHttpPage_WhenTheSocketOpens_ThenItConnectsToWsOnThePagesOwnHost', () => {
      vi.stubEnv('VITE_API_URL', '');
      pageAt('http:', 'localhost:3000');

      expect(open().client.config.brokerURL).toBe('ws://localhost:3000/ws');
    });

    it('GivenNoApiUrlOnAnHttpsPage_WhenTheSocketOpens_ThenItConnectsToWssOnThePagesOwnHost', () => {
      vi.stubEnv('VITE_API_URL', '');
      pageAt('https:', 'app.example.com');

      expect(open().client.config.brokerURL).toBe('wss://app.example.com/ws');
    });

    it('GivenAnHttpApiUrlWithATrailingSlash_WhenTheSocketOpens_ThenItConnectsToWsUnderThatUrl', () => {
      vi.stubEnv('VITE_API_URL', 'http://api.example.com/v1/');
      pageAt('https:', 'app.example.com');

      expect(open().client.config.brokerURL).toBe('ws://api.example.com/v1/ws');
    });

    it('GivenAnHttpsApiUrl_WhenTheSocketOpens_ThenItConnectsToWssOnThatOrigin', () => {
      vi.stubEnv('VITE_API_URL', 'https://api.example.com');
      pageAt('http:', 'localhost:3000');

      expect(open().client.config.brokerURL).toBe('wss://api.example.com/ws');
    });

    it('GivenARelativeApiUrl_WhenTheSocketOpens_ThenItConnectsToThePagesOwnHostAndIgnoresThePath', () => {
      // Pins what the code does today: a value that names no origin is not followed, although the axios
      // instance would prefix it to every request. VITE_API_URL is documented for another origin only.
      vi.stubEnv('VITE_API_URL', '/backend');
      pageAt('http:', 'localhost:3000');

      expect(open().client.config.brokerURL).toBe('ws://localhost:3000/ws');
    });
  });

  describe('its lifecycle', () => {
    it('GivenTheSocketIsOpened_WhenTheBrokerAcceptsIt_ThenItReportsConnectedAndSubscribesToTheUsersQueue', () => {
      const { client, onConnectionChange } = open();
      expect(client.activated).toBe(1);

      client.config.onConnect?.();

      expect(onConnectionChange).toHaveBeenCalledWith(true);
      expect(client.subscriptions).toEqual(['/user/queue/notifications']);
    });

    it('GivenAConnectedSocket_WhenTheWebSocketCloses_ThenItReportsDisconnected', () => {
      const { client, onConnectionChange } = openConnected();

      client.config.onWebSocketClose?.();

      expect(onConnectionChange).toHaveBeenLastCalledWith(false);
    });

    it('GivenAnOpenSocket_WhenItIsClosed_ThenTheClientIsDeactivated', () => {
      const { client, close } = open();

      close();

      expect(client.deactivated).toBe(1);
    });
  });

  describe('the frames it reads', () => {
    /** Holds what the socket queues as a microtask, so a test can run it and see what it throws. */
    function captureMicrotasks(): (() => void)[] {
      const queued: (() => void)[] = [];
      vi.stubGlobal('queueMicrotask', (task: () => void) => queued.push(task));
      return queued;
    }

    it('GivenAHandlerThatThrows_WhenANotificationArrives_ThenItsErrorIsReportedAndNotThrownIntoTheClient', () => {
      const queued = captureMicrotasks();
      const { client, onNotification } = openConnected();
      onNotification.mockImplementation(() => {
        throw new Error('the handler failed');
      });

      expect(() => client.deliver(JSON.stringify(notificationAsSent()))).not.toThrow();

      expect(queued).toHaveLength(1);
      expect(queued[0]).toThrow('the handler failed');
    });

    it('GivenAHandlerThatThrewOnce_WhenTheNextNotificationArrives_ThenItIsStillDelivered', () => {
      captureMicrotasks();
      const { client, onNotification } = openConnected();
      onNotification.mockImplementationOnce(() => {
        throw new Error('the handler failed');
      });
      const next = notificationAsSent({ id: 'n-2', title: 'Upgrade activated' });

      client.deliver(JSON.stringify(notificationAsSent({ id: 'n-1' })));
      client.deliver(JSON.stringify(next));

      expect(onNotification).toHaveBeenCalledTimes(2);
      expect(onNotification).toHaveBeenLastCalledWith(next);
    });

    it('GivenAConnectedSocket_WhenANotificationFrameArrives_ThenItReachesOnNotificationParsed', () => {
      const { client, onNotification } = openConnected();
      const pushed: AppNotification = notificationAsSent({ id: 'n-1', title: 'Upgrade activated' });

      client.deliver(JSON.stringify(pushed));

      expect(onNotification).toHaveBeenCalledTimes(1);
      expect(onNotification).toHaveBeenCalledWith(pushed);
    });

    it('GivenAConnectedSocket_WhenAFrameThatWillNotParseArrives_ThenItIsDroppedWithoutThrowing', () => {
      const { client, onNotification } = openConnected();

      expect(() => client.deliver('not json')).not.toThrow();

      expect(onNotification).not.toHaveBeenCalled();
    });

    it('GivenTheServerEndedTheSession_WhenItsErrorFrameArrives_ThenTheTokenIsDroppedAndItReportsDisconnected', () => {
      setAccessToken('live.jwt.token', '2099-01-01T00:00:00Z');
      const { client, onConnectionChange, onNotification } = openConnected();

      client.config.onStompError?.({ headers: { message: 'Session ended' } });

      expect(getAccessToken()).toBeNull();
      expect(onConnectionChange).toHaveBeenLastCalledWith(false);
      expect(onNotification).not.toHaveBeenCalled();
    });

    it('GivenAnErrorFrameForAnyOtherReason_WhenItArrives_ThenTheTokenIsKeptAndItReportsDisconnected', () => {
      setAccessToken('live.jwt.token', '2099-01-01T00:00:00Z');
      const { client, onConnectionChange } = openConnected();

      client.config.onStompError?.({ headers: { message: 'Failed to send message to ExecutorSubscribableChannel' } });

      expect(getAccessToken()).toBe('live.jwt.token');
      expect(onConnectionChange).toHaveBeenLastCalledWith(false);
    });
  });
});
