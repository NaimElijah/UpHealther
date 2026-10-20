# ADR-049: A session is live only for the account that owns it, and a borrowed one reads as an ended one

- **Status:** Accepted
- **Date:** 2026-10-20
- **Scope:** `backend/` — `SessionStatusPort` in `common`, `SessionQuery`, `SessionStatusAdapter` and
  `AuthSessionService` in `auth`, and `BearerTokenAuthenticator`, through which both transports
  authenticate. NFR-5 in `docs/requirements/requirements.md`. No schema change, no new dependency.
- **Issue:** [#286](https://github.com/NaimElijah/UpHealther/issues/286). It extends
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), whose session check
  asked only whether the `sid` was still live.

## Context

ADR-015 has `BearerTokenAuthenticator` ask `SessionStatusPort` whether the session an access token
names is still live. `AuthSessionService` answered by the session id alone.

An access token carries two claims that identify, and nothing tied them together:

- `sub` names the account, and the authenticator loads that account and its roles.
- `sid` names the session, and only its liveness was checked.

The server never issues a token whose two claims disagree. A sign-in opens the session for the account
it signs in, and a refresh issues the token for the session row's own owner. But neither id is a
secret:

- The session id is the first half of the refresh credential.
- Account ids appear in audit entries and in log lines, which NFR-6 allows.

So anyone holding the signing key could register, sign in to get a live `sid` of their own, and sign
`sub=<anyone>` with it. Roles come from the row, so the token would be whatever that account is,
administrator included. [ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md) removed the published
fallback key that once made this a few steps for anybody. A key can still leak, from a misconfigured
secret store or a log, and one live session should then not be enough to impersonate every account.

## Decision

**The owner is part of the question.**

- `SessionStatusPort.isActive(sessionId, userId)` and `SessionQuery.isActive(sessionId, userId)` ask
  whether the session is live *for that account*. The one-argument form is deleted, so nothing can ask
  about a session without naming whose it must be.
- `AuthSessionService` answers true only when the session exists, is live, and its owner is the account
  asked about. It reads the same row it read before.
- `BearerTokenAuthenticator` asks with the token's own `sub`.
- The answer stays one boolean. ADR-015 keeps the security adapter from knowing why a session
  cannot be used, and this does not change that.

**A borrowed session reads exactly as an ended one.** The token authenticates nobody, and the request
continues anonymously. The authenticator writes the same DEBUG line, with no ids. Nothing new is
audited: a bearer token on an HTTP request is not an audited authentication outcome (NFR-23), and a
STOMP CONNECT is refused and audited as it already was.

## Consequences

- **The token's two claims agree by construction.** A signing key alone no longer lets one live
  session act as any account, over HTTP or STOMP.
- **No extra cost.** The check filters the row the session check already reads.
- **The one sign of a leaked key is invisible in production.** The server never mints a mismatched
  pair, so a request carrying one means the key is out. It is logged at DEBUG, which
  `com.healthupgrades` does not emit at its INFO level (ADR-010), and nothing counts it.

## Alternatives considered

- **Have the port return the session's owner, and compare in the authenticator.** Rejected.
  - It gives the security adapter a richer answer than ADR-015 allows.
  - Every caller would then have to remember the comparison. With the owner in the question, a check
    that forgets it does not compile.
- **Log a borrowed session at WARN, or count it, as evidence of a leaked key.** Rejected for now.
  - Telling it apart from an ended session needs the port to say *why* it refused. That is the richer
    answer ADR-015 keeps out of `common`.
  - Nothing reads a WARN today: there is no deployment, and nothing pages anyone (#219). A detector no
    one is alerted by is noise.
  - **Revisit when** #219 gives a WARN or a metric somewhere to page. A refusal reason on the port,
    reported as a metric tag, is then the likely shape.
- **Drop `sub`, and take the account from the session row.** Rejected.
  - It would make a mismatch impossible rather than refused.
  - It changes the token's format and `JwtTokenProvider`'s contract, and every token in flight.
    Changing the port gives the same guarantee with the token unchanged.
  - **Revisit when** the token format changes for another reason.

## When to revisit

- **#219 lands alerting.** Reconsider whether a borrowed session should page someone.
- **The access token's format changes.** Reconsider deriving the account from the session.
