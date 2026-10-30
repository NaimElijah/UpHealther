/**
 * The live notification socket: STOMP over WebSocket, the one backend transport besides the axios
 * instance in `client.ts`.
 *
 * It owns everything about the connection: where it goes, the access token it presents, reconnecting,
 * reading each frame, and what an ERROR frame means for the session. Whoever opens it is told only about a
 * notification and whether the socket is connected.
 */
import { Client } from '@stomp/stompjs';
import { apiBaseUrl } from './apiBaseUrl';
import { renewSession } from './client';
import { endSession } from './session';
import { getAccessToken, hasUsableAccessToken } from './tokenStore';
import type { AppNotification } from '../types';

/**
 * The `message` of the ERROR frame the server closes a socket with once its session has ended: the
 * backend's `StompSessionRegistry.SESSION_ENDED`. `SessionEndedFrameContractTest` fails the backend build
 * if the two differ.
 */
const SESSION_ENDED = 'Session ended';

/**
 * Builds the WebSocket URL.
 *
 * Same-origin `/ws` through the Vite or nginx proxy by default, so the socket needs no CORS setup. On
 * the page's own origin the scheme follows the page's, since a `ws://` socket on an `https://` page is
 * blocked by the browser. Derived from `VITE_API_URL` only when the API is on another origin, whose
 * own scheme it keeps: `http` becomes `ws`, and `https` becomes `wss`.
 */
function buildWsUrl(): string {
  const apiUrl = apiBaseUrl();
  if (/^https?:\/\//.test(apiUrl)) {
    return apiUrl.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws`;
}

/** What the socket tells whoever opened it. */
export interface NotificationSocketHandlers {
  /** A notification the server pushed, already parsed from its frame. */
  onNotification: (notification: AppNotification) => void;
  /** `true` once the socket connects, `false` when it closes or the server answers with an ERROR frame. */
  onConnectionChange: (connected: boolean) => void;
}

/**
 * Opens the socket and subscribes to the signed-in user's notifications.
 *
 * It reconnects every five seconds while the socket is down, until it is closed. A frame that will not
 * parse is dropped. An error `onNotification` throws is rethrown on a microtask, so the browser reports
 * it as any uncaught error, and it never reaches the STOMP client: the next frame is delivered as usual.
 * The server's session-ended ERROR frame ends the session in this tab, through `endSession`; any other
 * ERROR leaves it alone.
 *
 * @param handlers told of each notification, and of each change in whether the socket is connected
 * @returns a function that closes the socket and stops it reconnecting
 */
export function openNotificationSocket({ onNotification, onConnectionChange }: NotificationSocketHandlers): () => void {
  const client = new Client({
    brokerURL: buildWsUrl(),
    // Read at connect time rather than captured when the socket was opened. The access token is short
    // lived and renewed underneath us, so a captured one would be stale by the first reconnect -
    // and reopening the socket on every renewal instead would tear it down and rebuild it every
    // fifteen minutes, losing the subscription each time for no reason.
    //
    // It is also renewed here when it has lapsed. Reading it without checking was an infinite
    // loop waiting to happen: a tab left idle past the token's fifteen minutes with no HTTP
    // traffic to renew it would reconnect with a dead token, be refused, and retry the same dead
    // token every five seconds - live notifications silently gone, and a refused CONNECT in the
    // server log every five seconds forever.
    beforeConnect: async () => {
      try {
        if (!hasUsableAccessToken()) {
          await renewSession();
        }
        const current = getAccessToken();
        client.connectHeaders = current ? { Authorization: `Bearer ${current}` } : {};
      } catch {
        // stompjs 7 awaits this hook and schedules no reconnect if it throws, so a failure here
        // would end the socket permanently rather than for one attempt. A connect with no header
        // is refused by the server, which is a reconnect rather than a dead client. The header is
        // cleared rather than left as the previous connect set it: a renewal refused for the rate
        // limit leaves the lapsed token in memory, and it would only be refused again. What this
        // loop should do instead is #384's.
        client.connectHeaders = {};
      }
    },
    reconnectDelay: 5000,
    onConnect: () => {
      onConnectionChange(true);
      client.subscribe('/user/queue/notifications', (message) => {
        let notification: AppNotification;
        try {
          notification = JSON.parse(message.body) as AppNotification;
        } catch {
          // A frame that will not parse is dropped rather than thrown: an exception here would
          // propagate into the STOMP client and tear down the subscription, costing every later
          // notification as well as this one.
          return;
        }
        try {
          onNotification(notification);
        } catch (error) {
          // The handler's own failure is a bug to see, not a frame to drop. Rethrown on a microtask,
          // it is reported as uncaught, and the STOMP client, which it would cost the subscription,
          // never sees it. `reportError` would do the same; jsdom 30 does not implement it.
          queueMicrotask(() => {
            throw error;
          });
        }
      });
    },
    onWebSocketClose: () => onConnectionChange(false),
    // The server ends a socket whose session has ended with this frame (ADR-054), so the tab ends the
    // session too, at once, rather than showing that session's data until its next request (S5.D2,
    // ADR-066). `endSession` drops the token, which could still look usable by its clock, empties the
    // query cache and leaves for the sign-in page. Any other ERROR leaves the session alone: Spring
    // sends the same generic one for every refused frame, an outage included, and ending the session
    // then would sign the tab out of a live session. A renewal that failed is not this frame either,
    // and is #377's to decide.
    onStompError: (frame) => {
      if (frame.headers.message === SESSION_ENDED) {
        endSession();
      }
      onConnectionChange(false);
    },
  });
  client.activate();

  return () => {
    client.deactivate();
  };
}
