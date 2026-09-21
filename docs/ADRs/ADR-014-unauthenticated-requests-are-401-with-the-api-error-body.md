# ADR-014: An unauthenticated request is a 401 with the API's error body, not the framework's 403

- **Status:** Accepted
- **Date:** 2026-09-16
- **Supersedes in part:** [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md), whose
  "What this does not close" section recorded the 403 and the missing body as known gaps
- **Scope:** `backend/` security chain and error handler, and the SPA's response interceptor. A wire
  contract change; no new dependency.

## Context

`SecurityConfig` configured no `AuthenticationEntryPoint` and no `AccessDeniedHandler`. Spring
Security therefore answered an anonymous request to a protected endpoint with its default
`Http403ForbiddenEntryPoint`, and the answer had two problems.

**The status was wrong.** 401 means "you have not identified yourself"; 403 means "I know who you are and
the answer is still no". An expired token leaves a request anonymous, so with a 24-hour token the most
common refusal a client sees was reported as a 403. The SPA's interceptor ended the session only on
401, so a tab left open across expiry kept failing without ever sending the user to sign in (#58).

**The body was the framework's.** The refusal is decided inside the filter chain, before any
controller, so `GlobalExceptionHandler` never saw it. The response carried Boot's default error body,
not `ErrorResponse`, and the trace id only in the header. `docs/api.md` had to list it as one of two
exceptions to "every failure has the same shape".

The requirements recorded the status question as open and owned by the repository owner, because
changing it breaks any client that branches on the status. The only client is this SPA, and the
refresh-token session work that follows needs a 401 it can react to.

## Decision

**Configure an entry point and an access-denied handler, and have both hand the refusal to Spring
MVC's exception resolver**, so `GlobalExceptionHandler` decides the status and writes the body as it
does for every other failure (`ErrorBodySecurityHandlers`).

- **An anonymous request, or one whose token was refused, is answered 401** with
  `WWW-Authenticate: Bearer` (RFC 6750 §3).
  - When a bearer token was presented the challenge adds `error="invalid_token"`.
  - Nothing distinguishes expired from forged from revoked: the holder of a stolen token learns
    nothing about whether it is worth retrying.
- **An authenticated request that is not allowed is answered 403.** It goes through the existing
  `AccessDeniedException` mapping.
- **The entry point forwards a new domain exception, `AuthenticationRequiredException`, not Spring's
  `InsufficientAuthenticationException`.**
  - The handler maps only the two credential failures among `AuthenticationException`s, deliberately,
    so an outage during login stays a 500.
  - Forwarding Spring's type would have reached the catch-all.
- **The resolver is injected by name (`handlerExceptionResolver`).** `DefaultErrorAttributes`
  implements the same interface and resolves nothing.
- **The SPA's interceptor now treats every 401 as a lost session, except on the sign-in and
  registration requests.** Their 401 is an answer to the form. The interceptor also used to reload the
  login page on a wrong password, which wiped the message.

## Consequences

- **Easier.**
  - A client can tell "sign in again" from "not yours" by the status alone.
  - Every failure the API produces now has the same body with the trace id in it. The only exception is
    a container error dispatch to `/error`, which ADR-007 still describes.
  - The refresh-token work has a precise signal to act on.
- **Harder.**
  - Any client that branched on the old 403 breaks. There are none outside this repository.
  - The entry point decides "was a token presented" from the `Authorization` header, because the filter
    leaves a refused token's request anonymous rather than recording why. If a second way of
    presenting a credential is added, the entry point must learn about it.

## Alternatives considered

- **Keep 403 and make the client treat 401 and 403 alike as a lost session.** Rejected: it fixes the
  tab, but leaves the API unable to say "you are signed in and this is not yours". The admin role that
  follows needs exactly that distinction.
- **`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`, the one-line change.** Rejected: right status, but
  still the framework's body and no challenge header. The error-body inconsistency would have stayed.
- **Write the JSON body in the entry point directly.** Rejected: a second place that builds
  `ErrorResponse` is a second place that can forget the trace id. The resolver route keeps one.
- **Adopt `spring-boot-starter-oauth2-resource-server` for its `BearerTokenAuthenticationEntryPoint`.**
  Rejected here as far too large a change for a status code; it is weighed on its own merits in
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), and rejected there too.
