# ADR-054: A session that ends closes its open sockets, with an ERROR frame, after the commit

- **Status:** Accepted
- **Date:** 2026-10-25
- **Scope:**
  - `backend/` — `BearerTokenAuthenticator` and `BearerAuthentication` in `common.security`;
    `StompPrincipal`, `JwtChannelInterceptor` and the new `StompSessionRegistry` in `common.websocket`;
    the new `LiveSocketPort` in `common.domain.port.out`; and in `auth`, `AuthSessionService`, the
    events `AuthSessionEnded` and `UserSessionsEnded`, and `SessionEndSocketListener`.
  - `frontend/` — `NotificationProvider`, which drops its access token on a STOMP ERROR frame.
  - FR-43, NFR-5 and NFR-35 in `docs/requirements/requirements.md`.
  - No schema change, no new dependency.
- **Issue:** [#83](https://github.com/NaimElijah/UpHealther/issues/83). It extends
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), which recorded the gap
  this closes as "a known gap, deliberately left".

## Context

ADR-015 makes ending a session take effect at once. Every HTTP request re-reads the session its token
names, so a signed-out token stops working on its next request.

A STOMP socket is different:

- `JwtChannelInterceptor` authenticates the CONNECT frame and authorises the frames after it.
- A connected socket sends nothing, so nothing arrives on it to check.
- So a socket opened before a session ended went on receiving the account's notifications until it
  next reconnected.

A session ends early in three ways:

- signing out;
- an administrator switching the account off, which revokes all of its sessions;
- a replayed refresh credential, which revokes that session for both holders.

All three left the account's open sockets live. Requirements FR-43, NFR-5 and NFR-35 each carried this
as a known deviation. On a shared or stolen device, signing out has to end the session's pushes too,
which is why #199 lists the gap as blocking a public launch.

The exposure was bounded. A socket only ever receives its own user's queue. But it was the one route by
which a session outlived its end.

## Decision

**The server closes a session's sockets when the session ends.**

1. **The socket remembers its session.**
   - The interceptor authenticates through `BearerTokenAuthenticator.authenticateWithSession`. This
     applies the same rules as `authenticate`, and also returns the `sid` the token was issued within.
   - `StompPrincipal` keeps that session beside the user id. Its name is still the user id, so routing
     is unchanged.
2. **A registry follows Spring's lifecycle events.**
   - `StompSessionRegistry` records each socket on `SessionConnectedEvent`, which carries the principal
     the interceptor attached.
   - It forgets the socket on `SessionDisconnectEvent`, which fires however the socket ends, a dropped
     connection included.
3. **`auth` announces the end, after the commit.**
   - `AuthSessionService` publishes `AuthSessionEnded` when it ends one session: a sign-out, or a
     replayed credential.
   - It publishes `UserSessionsEnded` when it ends all of an account's sessions. That bulk update does
     not report which sessions it ended, and every socket the account holds is to close regardless.
   - `SessionEndSocketListener` takes both with `@TransactionalEventListener(AFTER_COMMIT)` and calls
     `LiveSocketPort`.
   - `LiveSocketPort` is declared in `common` and implemented by the registry. The dependency is
     inverted as `SessionStatusPort` is, because `common → auth` would be a cycle.
4. **A socket is closed with a STOMP ERROR frame.**
   - The registry sends each matching socket an ERROR (`message: Session ended`) through the client
     outbound channel.
   - Spring's `StompSubProtocolHandler.sendToClient` closes the WebSocket after sending any ERROR frame
     (read in spring-websocket 7.0.9). So the frame ends the socket and tells the client why, through
     Spring's own serialised send path.
   - A send that fails is a WARN line naming the exception's type, and the other sockets still close.
5. **The client renews after an ERROR.**
   - `NotificationProvider` drops its access token on any ERROR frame, so its next attempt renews from
     the cookie.
   - Without this, the dead token is still unexpired by its clock. Every other tab of the session would
     reconnect with it every five seconds, be refused, and be audited as a refused `auth.connect`, until
     the token lapsed fifteen minutes later.

Sign-out is per device, so it closes only the sockets opened in that session. The account's other
devices stay connected.

## Consequences

- **Ending a session ends all of it.** A socket no longer outlives a sign-out, a switch-off or a
  replayed credential. The three known deviations are closed, and `StompSessionEndIT` drives two of
  the paths over a real socket.
- **#57 gets this for free.** A password change or an account deletion that revokes through
  `revokeAllForUser` closes the account's sockets with no further work.
- **A second piece of per-instance memory.** The registry lives in one process, as the broker does
  (requirements §5.3). A session ended on one instance could not close a socket held by another, so
  running two instances now needs a way to reach every instance's sockets, as well as the broker relay
  it already needed.
- **A short race is left open.** A CONNECT can pass its session check just before a revocation commits
  and be recorded after the registry's sweep. That socket stays open until it next reconnects. The
  window is milliseconds, the same one an HTTP request already in flight has.
- **A session's natural expiry closes nothing.** A session that idles out (7 days) or reaches its
  30-day cap ends by the passage of time, and nothing marks that moment. A socket that stayed quietly
  open past it keeps receiving until it reconnects. Filed as
  [#331](https://github.com/NaimElijah/UpHealther/issues/331).
- **A tab whose session ended is not sent to the sign-in page.** Its socket closes, its renewal is
  refused, and it reconnects without a token, which is not audited, until its next HTTP call ends the
  session in the interface. That is what a tab whose session expired already did.

## Alternatives considered

- **Check the session on the outbound channel, and drop a push to a dead session.** Rejected.
  - It costs a session read on every push.
  - It leaves the socket open and silent, which is worse to diagnose than a closed one.
  - #83 wrote this down as option 2 so that nobody rediscovers it.
- **Re-check the session on the client's heartbeats.** Rejected.
  - The delay is bounded rather than immediate.
  - The client sends no heartbeats today, and #210 deliberately proposes that it keeps sending none.
  - Heartbeats pass the interceptor untouched, so that no idle socket is closed by its keepalive.
  - #83 wrote this down as option 3.
- **Close the socket directly, through a `WebSocketHandlerDecorator` that holds every
  `WebSocketSession`.** Rejected. It needs a second registry keyed by the raw session, and a close
  from outside the send path races Spring's own writes. The ERROR frame goes through the path Spring
  already serialises, and it tells the client why.
- **Close the sockets inside the transaction that ends the session.** Rejected. A socket closed before
  the commit reconnects five seconds later. If the commit had not landed by then, that CONNECT would
  read the session as live, and the socket would stay open for good.
- **Close every socket of the account on any sign-out.** Rejected. Sign-out is per device (ADR-015),
  and the session is exactly what tells one device's sockets from another's.
- **Send the client to the sign-in page on the ERROR frame.** Not done here. A tab learns its session
  is over on its next HTTP call, as it always has. A redirect from a socket event is an interface
  change for its own issue.

## When to revisit

- **A second backend instance is planned.** The broker relay that requires has to carry a session's
  end to every instance's registry too, or the registry has to move out of the process.
- **A session's natural expiry needs to close its sockets** (#331). A sweep that finds the sessions that have
  just expired, and publishes their end the same way, is the likely shape.
- **The registry's sweep becomes measurable.** It walks every open socket on every session's end. At
  one instance's socket count this is nothing. An index by session and by account is the next step if
  it ever is not.
