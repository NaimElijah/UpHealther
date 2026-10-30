# ADR-067: Trace context is accepted only from trusted hops

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/` — `StompTracingChannelInterceptor`, and `management.tracing.propagation.consume` in
    `application.yml`.
  - `frontend/nginx.conf` — the proxy clears the trace headers a client sends, on the branch of
    [#381](https://github.com/NaimElijah/UpHealther/pull/381).
  - NFR-21 in `docs/requirements/requirements.md`.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), scan 05 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334): finding S5.C5, decided by the owner. It
  extends [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md), whose revisit trigger
  this is: "when this service sits behind a proxy that can strip client-supplied headers, at which point
  inbound propagation should be accepted only from that proxy".

## Context

ADR-007 made the trace id the key that joins a request's log lines, its error body and its audit
entries, and NFR-21 promised that an inbound W3C `traceparent` is continued rather than replaced. Every
inbound one was, whoever sent it. So the client chose the id that the logs and the audit trail join on:
it could give two unrelated requests the same id, or the id of somebody else's request, and an
investigation reading the trail by trace id would join them (S5.C5).

Three paths carried a client's choice in:

- **HTTP `traceparent`.** The service sits behind nginx, which can strip a header before it reaches the
  backend, and which is the trigger ADR-007 named.
- **HTTP B3.** Boot 4.1.1 consumes `W3C`, `B3` and `B3_MULTI` by default
  (`management.tracing.propagation.consume`), and `application.yml` set nothing. A client's `b3` header,
  or `X-B3-TraceId` with `X-B3-SpanId`, was continued whatever was done about `traceparent`.
- **STOMP frames.** `StompTracingChannelInterceptor` extracted trace context from every frame's native
  headers. A frame comes from the client over an open socket, past anything nginx can strip.

## Decision

**Trace context is continued only from a hop the service trusts, and nginx is the only one.**

- **nginx clears what a client sends.** `traceparent`, `tracestate`, `b3` and the five `X-B3-*` headers
  are cleared on every proxied request. That change lands through #381.
- **The backend consumes W3C only.** `management.tracing.propagation.consume: W3C`, so B3 in either form
  is ignored even if it reaches the backend. What the backend produces is unchanged, W3C as before.
- **A STOMP frame from a client never continues a trace.** The interceptor no longer reads native
  headers. A frame starts a span of its own, a child of whatever span is current on the sending thread:
  none for a frame the client sent, so it starts a fresh trace, and the request's own for a frame the
  server sends while serving one, so a push still joins the request that caused it.

## Consequences

**Easy.** The trace id in a log line or an audit entry is one the service minted, or one nginx
forwarded, so a trail joined by it joins what the service actually did.

**Hard.**

- Over HTTP, the backend cannot tell nginx's `traceparent` from a client's. The guarantee holds only
  while every request reaches the backend through nginx.
- A client that wants its own spans correlated with the server's, which this SPA does not, now gets a
  fresh trace instead. The `X-Trace-Id` header still tells it which one.
- A tracing system upstream of nginx, should one ever be added, will not be continued until nginx is
  told to pass its headers, and this record is revisited.

## Alternatives considered

**Keep continuing client trace context.** Rejected: it leaves the client choosing the key the logs and
the audit trail join on, which is what S5.C5 is. **Revisit** if a client is ever trusted, a first-party
service calling the API rather than a browser, and then only for that client.

**Validate the client's trace context instead of discarding it.** Rejected: validation can check that a
`traceparent` is well formed, never who chose it, and a forged one is exactly as well formed as a real
one. There is nothing to validate it against.
