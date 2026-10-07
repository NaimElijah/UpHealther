# ADR-024: The container's error page keeps its status and carries the trace id

- **Status:** Accepted
- **Date:** 2026-10-07
- **Scope:** `SecurityConfig`'s authorisation rules, `TraceIdResponseHeaderFilter`, and a new
  `TraceIdErrorAttributes`. Extends [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md)
  and [ADR-014](ADR-014-unauthenticated-requests-are-401-with-the-api-error-body.md). No new dependency.
- **Issue:** [#105](https://github.com/NaimElijah/UpHealther/issues/105)

## Context

Most failures never leave Spring MVC. `GlobalExceptionHandler` answers them on the request's own
dispatch, and it puts the trace id on the body (NFR-21). Two kinds of failure cannot reach it:
- an exception that escapes a servlet filter, such as a database outage inside the bearer-token check;
- a request that Spring Security's `StrictHttpFirewall` refuses. The firewall answers with
  `sendError(400)`.

Tomcat renders both through an **ERROR dispatch** to `/error`, where Boot's `BasicErrorController`
builds the body from `ErrorAttributes`.

The issue recorded one symptom: the trace id was in the header but not the body. Boot registers
`ServerHttpObservationFilter` for `REQUEST` and `ASYNC` dispatches only. Even registered for `ERROR`, it
would skip one, because it inherits `OncePerRequestFilter`'s default. By the time `/error` runs, the
request's observation has closed, so nothing on that dispatch can read a trace id from the tracer.
Only `X-Trace-Id` survived, because `TraceIdResponseHeaderFilter` set it before the chain ran, and
Tomcat resets a response's buffer but not its headers.

A real-port test found a second symptom, and it is worse:
- Spring Security 6 authorises **every** dispatcher type, and `/error` was not permitted.
- The ERROR dispatch carries no credentials, because `JwtAuthenticationFilter` skips error dispatches,
  and the stateless chain keeps nothing between dispatches. So it was refused as anonymous.
- A malformed URL that should have been a 400, or an outage that should have been a 500, reached the
  client as **401 `Bearer`**. That is the answer that tells the SPA its session lapsed: it renewed a
  token that was fine and retried. NFR-7 was broken on every such path.

## Decision

**Let the container's ERROR dispatch through security, and carry the request dispatch's trace id onto
Boot's error body.**

- **`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`** is the first authorisation rule.
  - It matches the dispatcher type, not the path. A client cannot make an ERROR dispatch. A client's
    own `GET /error` is a REQUEST dispatch, so it still needs a token, which
    `AuthenticatedBoundaryTest` pins.
  - What the page may say is bounded by Boot's `server.error.include-*` defaults. In 3.2 the message,
    stack trace and binding errors are all `never`, and the exception is excluded. The body is
    `timestamp`, `status`, `error` and `path`, and none of those defaults is overridden. They used to
    guard a page nobody could reach; now they guard one anybody can trigger, so changing them is a
    security decision.
- **`TraceIdResponseHeaderFilter` records the id it puts on the header as a request attribute.**
  `TraceIdErrorAttributes`, which extends Boot's `DefaultErrorAttributes`, adds that attribute to the
  error body as `traceId`, under the same name `GlobalExceptionHandler` uses.
  - Header and body agree by construction: both are the one value read through
    `CorrelationId.of(tracer)` while the request's scope was open.
  - An untraced request gets no `traceId` key, as on every other error body.
- **Boot's body shape stays.** The SPA reads `traceId` and `message` from any error body. It falls back
  to the header for the id and to a generic sentence for the message, so this page needs no message to
  be useful.

## Consequences

- A request the firewall refuses is answered 400, and an exception escaping a filter is answered 500.
  Both carry the same id in the header and the body. Neither is mistaken for a lapsed session.
- `ErrorAttributes` is now this application's bean, and Boot's backs off. The error body's fields are
  Boot's, plus one.
- The error dispatch still runs outside any observation. Anything logged during it would carry no trace
  id. Nothing logs there today.
- A request Tomcat itself refuses before any filter runs, such as an encoded `/` or `\` in the path, is
  answered by Tomcat directly and carries no id at all.
- A failure that escapes before `TraceIdResponseHeaderFilter` has run gets a body with no `traceId`.
  Only the character-encoding filter and the observation filter run earlier.

## Alternatives considered

- **Permit the `/error` path.** This is the common recipe. Rejected: it would also open a client's own
  REQUEST dispatch to Boot's error controller without a token. A dispatcher type is the narrower key.
- **Put the trace id on the 401.** Rejected: it fixes the symptom #105 recorded and leaves the wrong
  status, which is the worse bug.
- **Read the span from the observation context** that `ServerHttpObservationFilter` leaves on the
  request. Rejected: it reaches past `CorrelationId`, which owns the three cases of "no id", into
  Micrometer's handler internals.
- **Register the observation filter for ERROR dispatches.** Rejected: the filter skips them anyway
  unless subclassed, and replacing Boot's registration changes the observation of every request to fix
  one page.
- **A custom `ErrorController` rendering the API's own `ErrorResponse`.** Rejected for now: it is more
  code and it replaces Boot's controller, and nothing a client does needs the second shape.
- **A `RequestRejectedHandler` that answers firewall rejections itself.** Rejected: it fixes the
  firewall path only, not an exception escaping a filter.

## When to revisit

- **Spring opens an observation scope for ERROR dispatches** (a future `ServerHttpObservationFilter`).
  The request attribute can then go, and `TraceIdErrorAttributes` can read through `CorrelationId` like
  everything else.
- **Anyone changes a `server.error.include-*` property.** That decides what an unauthenticated caller
  can make this page say.
- **A client needs one body shape everywhere**, for example a `message` on the error page. That is the
  point at which a custom `ErrorController` earns its place.
