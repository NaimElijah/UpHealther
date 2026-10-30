# ADR-070: nginx throttles the WebSocket handshake per client address, and caps no sockets

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:** `frontend/nginx.conf` — a `limit_req_zone` and its use in `location /ws`; NFR-32 in
  `docs/requirements/requirements.md`; `frontend/src/test/nginxConf.test.ts`. No backend change.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), finding S5.C4. It extends
  [ADR-017](ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md), which rejected
  `limit_req_zone` for sign-in and registration.

## Context

The `/ws` handshake is anonymous: a browser cannot set headers on it, so the token travels later, in the
STOMP CONNECT frame. Anyone can open a socket, and the backend logs a refused frame and an undecodable
one at ERROR (spring-websocket 7.0.9). Nothing limited how fast a client could open sockets, so an
anonymous client could write ERROR lines as fast as it could connect, which is alert noise on demand.

ADR-017's in-process limiter covers two endpoints, sign-in and registration, matched the way the
handler mapping matches. The handshake is not a handler-mapped request, and is outside both.

ADR-017 rejected nginx's `limit_req_zone` for those two endpoints: a refusal there carries no API error
body and no trace id, is invisible to the application's metrics, cannot be tested here, and is bypassed
by anything reaching `:8080` directly. Those costs are real for `/ws` too, but smaller: the handshake has
no error body to carry, a refused handshake costs the browser only its next attempt five seconds later,
and `:8080` is published on loopback only (ADR-057).

## Decision

- **nginx throttles `/ws` handshakes per client address,** keyed on `$binary_remote_addr`:
  `rate=60r/m`, `burst=20 nodelay`, answered `429`.
  - A tab holds one socket and, while it is down, reconnects every five seconds: twelve handshakes a
    minute (`api/notificationSocket.ts`). Sixty a minute lets five tabs on one address reconnect
    without pause at the rate.
  - The burst of twenty lets twenty tabs reconnect at once, as they do when the backend restarts,
    without waiting.
  - `nginxConf.test.ts` holds the rate to at least five tabs' worth, and requires the burst and the
    `429`.
- **No cap on open sockets.** A `limit_conn` was tried and removed. Everyone behind one address shares
  any per-address cap: a household's router, a carrier's NAT, and every visitor once a load balancer
  sits in front (#208). One account holding the cap's worth of sockets would deny the others theirs,
  and a tab refused its socket also misses the session-ended frame (ADR-066). An authenticated socket
  legitimately stays open for an hour. The handshake rate already bounds the churn the finding is about.
- **The limit bounds handshakes, not what one socket sends.** One message of many frames on an open
  socket can still write many ERROR lines; [#382](https://github.com/NaimElijah/UpHealther/issues/382)
  tracks that.

## Consequences

**Easy.** A client that connects in a loop is slowed to a minute's sixty handshakes and refused with 429
past the burst, without the application doing anything. A normal client never meets the limit.

**Hard.**
- One address carries every tab behind it. Through the compose stack's loopback port, Docker usually
  delivers every connection from its gateway's address, so every local tab shares one allowance.
- The limit assumes nginx is the first hop and `$remote_addr` the client. Behind a load balancer every
  visitor would be one address, and the limit would throttle them all together, until
  `set_real_ip_from` and `real_ip_header` restore the client's address (#208, #224).
- An IPv6 client is counted per address, not per /64 as ADR-017's limiter counts it, so one that rotates
  addresses within its allocation gets a fresh allowance for each.
- A refusal is nginx's own 429, with no trace id, and is counted nowhere the application can see.

## When to revisit

- **A load balancer goes in front of nginx** (#208, #224): trust its address with `set_real_ip_from`
  first, or the limit becomes one for everybody.
- **A normal client meets the limit**, more tabs per address than the rate allows, for example.
- **#382 is fixed in the backend**, which may make the edge limit less necessary.
- **A second backend instance is planned**, when the throttle may belong in the shared store ADR-017's
  revisit trigger names.

## Alternatives considered

- **`limit_conn` on `/ws` as well.** Rejected; see the Decision.
- **A limit in the backend, beside ADR-017's.** Rejected for now: the handshake is not a handler-mapped
  request, so ADR-017's interceptor cannot see it, and a handshake limiter of its own is more code, to
  test and to keep bounded, than one nginx rule. It becomes the better place if the throttle has to
  carry a trace id or hold across instances.
- **No limit.** Rejected: the handshake is the one anonymous way in that writes ERROR lines.
