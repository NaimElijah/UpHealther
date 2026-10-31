# ADR-078: A refused STOMP frame is answered in fixed words, and the SPA acts only on Session ended

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `backend/` — `StompRefusalErrorHandler` and `StompFrameRefusedException` in `common.websocket`, the
    handler's registration in `WebSocketConfig`, and `JwtChannelInterceptor`, which throws the exception.
  - `frontend/` — `api/notificationSocket.ts`, whose `onStompError` is unchanged.
- **Issue:** [#341](https://github.com/NaimElijah/UpHealther/issues/341), scan 07 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding A7.6. Amends
  [ADR-054](ADR-054-a-session-that-ends-closes-its-open-sockets.md) and
  [ADR-066](ADR-066-a-tab-keeps-nothing-of-a-session-that-ended.md), which both say Spring answers every
  refused frame with one generic message.

## Context

`JwtChannelInterceptor` refuses a STOMP frame by throwing from `preSend`, the only way an interceptor
can. `AbstractMessageChannel.send` wraps what it throws, and with no error handler registered,
spring-websocket 7.0.9 writes the wrapper's message into the ERROR frame. So every refusal read
"Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]": it named a class inside
the server, and a client could tell no refusal from an outage. The same path wrote a decoder's
`StompConversionException` text, which quotes client input, and Spring's `Unknown session: <id>`.

ADR-054 and ADR-066 built on that. Because one message covered every cause, the SPA could not tell a
dead token from a database outage, so it acts on no ERROR but the server's own `Session ended`, which
`StompSessionRegistry` sends on the outbound channel when a session ends.

## Decision

**A registered `StompSubProtocolErrorHandler` words the ERROR frame for any client frame the server did
not process, choosing the words by the type of what was thrown and never copying its text. There are
three, and `Session ended` passes through untouched.**

- `Unauthenticated`: a CONNECT with no bearer token or one that does not authenticate. The interceptor
  throws `StompFrameRefusedException` with reason `UNAUTHENTICATED`.
- `Refused`: a SUBSCRIBE or SEND on a connected socket that the interceptor turns down, reason `REFUSED`.
- `Frame not processed`: anything else. That covers an outage while authenticating, a frame that could
  not be decoded, and a SUBSCRIBE or SEND before any CONNECT, which Spring refuses (`Unknown session`)
  before the interceptor runs.

The handler finds a `StompFrameRefusedException` anywhere in the cause chain; failing that, it writes
`Frame not processed`. It overrides only `handleClientMessageProcessingError`. The `Session ended` frame
goes out through `handleErrorMessageToClient`, which Spring's default passes on unchanged, so its words
stay exactly what `SessionEndedFrameContractTest` pins. Every ERROR frame still closes the socket.

**The SPA deliberately acts on none of the three.** `notificationSocket.ts` ends the session only on
`Session ended`, as ADR-066 decided, and keeps its token through any other ERROR. The words now let it
tell a dead token from an outage, but what a tab should do about a refused CONNECT is #384's decision,
and what a failed renewal does is #377's. This change gives the client the words and leaves the
behaviour to those issues.

## Consequences

**What this makes easy**

- **No exception text, token or class name reaches a client.** The words are a closed set chosen here.
- **A client can now tell a refusal from a failure,** and an operator reading a browser's console sees
  which one it was.

**What this makes hard**

- **Three more words are a wire contract.** Renaming one breaks any client that reads it. api.md, NFR-32
  and `StompRefusalIT`, which asserts them as literals, are where they are pinned.
- **A new kind of refusal needs a new reason.** An exception the interceptor throws that is not a
  `StompFrameRefusedException` reads as `Frame not processed`, which is safe but uninformative.

## Alternatives considered

- **Leave Spring's message.** Rejected: it named a server class, and on other paths copied exception
  text that can quote client input.
- **Copy the interceptor's own exception message.** Rejected: that is server detail, and the first
  message to carry a value would leak it.
- **Make the SPA act on `Unauthenticated`, dropping its token or renewing.** Not now: #384 owns the
  socket's reconnect loop and #377 what a failed renewal does, and both are undecided.

## When to revisit

- **A client should drop its token, or renew, on `Unauthenticated`**, which is #384's question, or when
  #377 decides what a failed renewal does. The SPA's `onStompError` then reads these words, and they
  become a contract the frontend depends on.
- **A second client consumes the socket.** The words would then need versioning like any other contract.
