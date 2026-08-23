# ADR-006: Framework exceptions are mapped by Spring's own handler list, not one at a time

- **Status:** Accepted
- **Date:** 2026-08-23
- **Scope:** `backend/` inbound web adapter — `GlobalExceptionHandler` and the wire contract for error
  responses. No new dependency; no change to the error body's shape.

## Context

`GlobalExceptionHandler` is a `@RestControllerAdvice` that maps each domain exception to a status and
ends with a catch-all:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) { ... 500 ... }
```

An advice is consulted by `ExceptionHandlerExceptionResolver`, which runs **before** Spring's own
`DefaultHandlerExceptionResolver`. So the catch-all did not merely handle what was left over: it
claimed every framework exception first, and the status Spring defines for that exception never
applied. Measured against the advice as it stood, on `UpgradeController` alone:

| Request | Returned | Should be |
|---|---|---|
| `POST /api/upgrades` with `{"type":"NOT_A_TYPE"}` | 500 | 400 |
| `GET /api/upgrades/not-a-uuid` | 500 | 400 |
| `GET /api/upgrades?status=BOGUS` | 500 | 400 |
| `PATCH /api/upgrades/{id}` | 500 | 405 |
| `POST /api/upgrades` with `Content-Type: text/plain` | 500 | 415 |

Every one of these is the caller's mistake reported as the server's failure. Each was also counted as
an unhandled failure, which is the noise a real 500 has to be found in.

[Issue #22](https://github.com/NaimElijah/UpHealther/issues/22) described the first row. The fix for it
alone — one more `@ExceptionHandler` — left the other four live and turned the class into a list of
special cases to be extended each time a user found the next one in production.

## Decision

`GlobalExceptionHandler` **extends `ResponseEntityExceptionHandler`**, inheriting Spring's mapping for
every standard Spring MVC exception, and overrides `handleExceptionInternal` to re-clothe the result in
this API's `ErrorResponse` body instead of a `ProblemDetail`.

Three hooks are overridden where the inherited message is not good enough:
`handleMethodArgumentNotValid` (keeps the `field → message` map), `handleHttpMessageNotReadable`
("Malformed request body") and `handleTypeMismatch` ("Malformed request parameter"). Everything else
takes the status' own reason phrase, which is accurate and cannot leak the exception's detail.

The catch-all stays, for exceptions that are genuinely ours, and now logs at ERROR with the stack
trace. A 4xx produced by the inherited handlers logs at DEBUG: a caller's mistake is not an incident.

To change how a framework exception surfaces, override its `handleXxx` hook. Adding a second
`@ExceptionHandler` for a type the parent already maps is an ambiguous mapping and fails at startup;
`GlobalExceptionHandlerTest` builds the resolver the way Spring does, so that failure surfaces in
`mvn test` rather than in a boot log.

## Consequences

**Easier.** Every standard MVC exception has the right status without anyone deciding to add it. New
endpoints inherit the behaviour. The error body stays one shape across domain, framework and unmapped
failures, so a client parses one thing. A 500 in the log is now evidence of an actual server fault.

**Harder.** The advice's behaviour is no longer readable from the class alone — part of the contract
lives in a superclass whose list of handled types changes with the Spring version. A Spring upgrade
that adds a handled type, or changes one's status, changes this API's wire contract; that is what
`GlobalExceptionHandlerTest` is for, and its failure on an upgrade is the signal, not a nuisance.

Five statuses changed for clients: the four rows above plus an unmatched route, which was a 500 and is
now a 404. No client branches on any of them — `frontend/src/api/client.ts` inspects 401 and nothing
else — so this was taken in one step rather than staged.

## Alternatives considered

- **One `@ExceptionHandler` per framework exception, added as each is discovered.** Rejected. It is
  the approach that produced the bug: "discovered" means a user hit a 500 first, and the list is never
  finished because it is the framework's list, not ours. It also duplicates knowledge Spring already
  has about which exception deserves which status. *Revisit if* we ever need per-exception bodies
  divergent enough that the inherited hooks stop paying — at which point the overrides are the
  extension point anyway.
- **Deleting the catch-all so `DefaultHandlerExceptionResolver` runs.** Rejected: it fixes the
  framework exceptions by giving up the guarantee that an unexpected failure never reaches the client
  as a stack trace, and Boot's default error page has a different body shape than every other error
  this API returns.
- **Returning Spring's `ProblemDetail` (RFC 9457) instead of `ErrorResponse`.** Rejected for now. It is
  the better-standardised body and Spring produces it for free, but adopting it is a breaking change to
  every error response the frontend already reads, for a benefit no current client asks for.
  *Revisit when* a second client — a mobile app, a public API consumer — has to integrate, since that
  is the point at which a standard error format starts paying for itself.
- **Echoing Spring's `ProblemDetail.detail` as the message for 4xx.** Rejected: only some of these
  exceptions implement `ErrorResponse` (`MethodArgumentNotValidException` does,
  `HttpMessageNotReadableException` and `TypeMismatchException` do not), so the message would be
  detailed for some statuses and absent for others, with no rule a client could rely on.
