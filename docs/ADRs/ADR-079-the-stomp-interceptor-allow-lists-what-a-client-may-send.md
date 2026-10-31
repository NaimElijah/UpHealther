# ADR-079: The STOMP interceptor allow-lists the commands a client may send

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `backend/` — `JwtChannelInterceptor.preSend` in `common.websocket`, and the wording of
    `StompFrameRefusedException`'s Javadoc.
  - `JwtChannelInterceptorTest`, which refuses each command in turn, and `StompRefusalIT`, which sends a
    client's MESSAGE frame over a real socket.
- **Issue:** [#342](https://github.com/NaimElijah/UpHealther/issues/342), scan 08 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D1.1. Extends
  [ADR-078](ADR-078-a-refused-stomp-frame-is-answered-in-fixed-words.md), whose `Refused` now answers any
  frame the interceptor turns down on a connected socket, not only a SUBSCRIBE or a SEND.

## Context

`JwtChannelInterceptor` authenticates a CONNECT and authorises the frames after it. Its `switch` on the
STOMP command refused one command, SEND, and a `default` branch passed everything else. That was a
deny-list of one, and it assumed a client sends only the commands a client is meant to send.

spring-websocket 7.0.9 makes no such assumption. `StompSubProtocolHandler` decodes every STOMP command
from a client, the four that STOMP defines for the server to send included: MESSAGE, CONNECTED, RECEIPT
and ERROR. A client's MESSAGE maps to `SimpMessageType.MESSAGE`, the same type as a SEND, so
`UserDestinationMessageHandler` and the simple broker route it as they would a SEND. A signed-in account
that knew another account's user id could therefore send a MESSAGE frame to
`/user/<victimId>/queue/notifications` and have its own text shown in the victim's tab as a notification,
a toast and an OS notification. The interceptor never saw anything wrong, because MESSAGE reached the
`default` branch.

It was reproduced in-process with Spring's real handlers before the fix, and is pinned after it by
`JwtChannelInterceptorTest`, which asserts each refused command, and by `StompRefusalIT`, which asserts
that a client's MESSAGE frame is answered `Refused`. The API shows a user id only to administrators,
which bounded the exposure to an account that had been told or had read one. Nothing had been deployed
publicly.

The SPA subscribes in STOMP's default `auto` ack mode and opens no transaction, so it never sends ACK,
NACK, BEGIN, COMMIT or ABORT. The application declares no `@MessageMapping`, so a SEND has nothing
server-side to reach.

## Decision

**`JwtChannelInterceptor.preSend` is an allow-list of STOMP commands. A command it does not name is
refused.**

- **CONNECT and STOMP authenticate.** STOMP is the 1.2 spec's alias for CONNECT, and is handled alike.
- **SUBSCRIBE is authorised:** it must come from an authenticated session, name
  `/user/queue/notifications` exactly, and be the socket's first, as before. It must also be in `auto`
  ack mode, named or left to the default: STOMP 1.2 obliges a subscriber in `client` or
  `client-individual` mode to ACK each MESSAGE, and an ACK is refused, so such a subscription would be
  refused at its first acknowledgement and reconnect into the same refusal for ever. It is refused at
  SUBSCRIBE instead, once.
- **UNSUBSCRIBE and DISCONNECT pass.** An UNSUBSCRIBE names only one of the socket's own subscription ids
  and frees nothing the limit counts. Refusing a DISCONNECT would leave sessions to time out instead of
  closing cleanly.
- **A heartbeat passes.** It carries no command, and refusing one would close every idle socket. It is
  the only message without a command that passes: one Spring has not marked as a heartbeat, or one with
  no STOMP headers at all, is no frame Spring's own senders produce, and is refused.
- **Every other command is refused** with ADR-078's `Refused`: SEND; MESSAGE, CONNECTED, RECEIPT and
  ERROR, which only the server sends; and ACK, NACK, BEGIN, COMMIT and ABORT, which nothing here needs.
  The refusal is logged by naming the command, an enum and never client text.

ADR-078 said `Refused` answers a SUBSCRIBE or SEND on a connected socket. It now answers any frame on a
connected socket that the interceptor turns down. The three words, and how the handler chooses them from
the exception's type, are unchanged.

**What did not change.** A refusal after CONNECT is still a WARN line and not an audit entry, as
[ADR-021](ADR-021-what-a-refused-authentication-names-and-what-is-no-attempt.md) records the owner
deciding on #98: it authorises a frame on a connection that is already authenticated.
Spring still logs an ERROR line of its own for a refused frame of any command but CONNECT and SUBSCRIBE,
which no interceptor can suppress; that volume is S5.C4, tracked in #382.

## Consequences

**What this makes easy**

- **A command nobody thought of is refused.** The next command spring-websocket decodes, or STOMP adds,
  fails closed.
- **The list of what a client may do is one `switch`,** beside the Javadoc that says why each passes.
- **No account can write into another's notifications.** A MESSAGE frame is refused before it reaches the
  broker.

**What this makes hard**

- **A feature that needs a new command has to open it deliberately.** A client that must send (a
  `@MessageMapping`), a subscription in `client` ack mode or a transaction fails with `Refused` until the
  interceptor names it, which is the point, but it is a step a developer will meet as a surprise.
- **An unexpected command from a stray client now closes its socket.** Every ERROR frame closes the
  socket, so a client library that sends an ACK on its own fails its connection rather than being ignored.
- **Each refused command adds Spring's own ERROR line.** The refusal still counts toward #382's volume.

## Alternatives considered

- **A deny-list that adds MESSAGE, CONNECTED, RECEIPT and ERROR beside SEND.** Rejected: it fails open for
  the next command nobody thought of, which is the gap this change closes. It also leaves ACK, NACK and
  the transaction commands reachable for no benefit.
- **Refuse at the decoder, or in a custom `StompSubProtocolHandler`.** Rejected: more framework surface
  to own and keep in step with Spring upgrades, and the interceptor is already where every inbound frame
  is authorised and where ADR-078's refusal is thrown from.
- **Let ACK, NACK and the transaction commands pass because they name no destination.** Rejected: least
  privilege. Nothing sends them, so passing them gains nothing and leaves a path that nothing tests.

## When to revisit

- **A feature needs a client to send.** A `@MessageMapping` reopens SEND, and the interceptor then needs
  to authorise its destinations as it does a subscription's.
- **A subscription in `client` ack mode, or STOMP transactions.** The SUBSCRIBE check would then accept
  that mode and ACK and NACK be named in the allow-list, or BEGIN, COMMIT and ABORT would.
- **A Spring release whose handler refuses the server-only commands from a client itself.** The
  interceptor's refusal of MESSAGE, CONNECTED, RECEIPT and ERROR would then be a second line of defence
  rather than the only one, and might be dropped.
