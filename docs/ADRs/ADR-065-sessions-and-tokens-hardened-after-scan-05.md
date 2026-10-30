# ADR-065: Sessions and tokens hardened after scan 05

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:** `backend/` — the `auth` context's sessions, refresh cookie and their configuration, the
  rate limit in front of the cookie endpoints, how the audit stream names a session, and migration `V10__auth_session_spent_tokens.sql`;
  NFR-4, NFR-24, NFR-34, NFR-36, NFR-47 and NFR-58 in `docs/requirements/requirements.md`.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), scan 05 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334): findings S5.A1, S5.A4, S5.A8, S5.A9,
  S5.A11, S5.A13, S5.A14 and S5.C3.
  It extends [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), whose reuse
  detection it makes hold for any number of rotations and on both cookie endpoints, and
  [ADR-041](ADR-041-the-refresh-cookie-is-always-secure.md), whose cookie it names, and
  [ADR-017](ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md), whose limiter it uses a
  second time.

## Context

Scan 05 read the session code against ADR-015's promise that a stolen refresh credential is usable at
most once, and that using it is detected, and the settings and limits around it. It found:

- **S5.A1, reuse after two rotations went undetected.** The session row kept the digest of the current
  credential and of the one before it, and nothing older. A thief holding C1 who refreshed C1 → C2 → C3
  left the victim holding a credential that matched neither digest, so it was refused as a guess: a
  401, no revocation, no WARN, no `AUTH_TOKEN_REUSE`. The thief's session lived on to the thirty-day
  cap. A guess has to stay harmless, because the session id is no secret; what was missing was the means
  to tell a credential the session spent two rotations ago from one it never issued.
- **S5.A4, sign-out with a spent credential left the session alive.** Sign-out refused a credential
  rotated out after its grace window, so the victim of a thief who had refreshed once was answered 204,
  saw themselves signed out, and left the thief signed in, with nothing recording the reuse.
- **S5.A9, refresh never re-read the account.** Switching an account off ends the sessions it holds,
  but a session opened between a sign-in's password check and the switch was not there to end, and it
  then rotated for thirty days.
- **S5.A8, the cookie could be planted.** It was named `refresh_token`, with no prefix, and a request
  carrying two was answered from the first. A same-site sibling host could set a second, more specific
  one and move the victim's tab into the attacker's account.
- **S5.A11, refresh and sign-out were unthrottled.** Both are anonymous, and each attempt reads a
  session under a lock and writes an audit entry, so a caller could write the audit stream at will.
  ADR-021 names this trigger itself.
- **S5.A13, the lifetimes were unbounded.** `AUTH_SESSION_ROTATION_GRACE=30d` would forgive every
  replay, which turns reuse detection off, and nothing would report it.
- **S5.A14, a token without `exp` was accepted.** JJWT checks the expiry only when a token carries one,
  so a token signed with the key and no `exp` would never lapse.
- **S5.C3, the audit stream listed live sessions beside their owners.** Every refresh writes the
  account's id and the session's, and that pair is what ADR-049 says a signing key alone lacks to forge
  a token for an existing account.

## Decision

### 1. Every spent refresh credential is remembered (S5.A1)

**A session remembers every credential it has spent.** Each rotation retires the current digest as a
`SpentCredential`, its digest and the instant it was retired, and the service stores it in
`auth_session_spent_tokens` within the rotation's transaction, under the session's row lock. The rows
cascade with their session, so the nightly sweep and an account's deletion take them too, and a
session's history never outlives it.

**The grace window runs from the moment each credential was spent.** A spent credential presented within
`rotationGrace` of its own retirement is stale (409, retry); after it, the session is revoked as reuse,
with the same WARN, `AUTH_TOKEN_REUSE` entry and published end as before. Two quick rotations therefore
do not turn a request that was already in flight into a theft.

**The aggregate no longer holds a previous digest.** `previous_token_hash` and `rotated_at` are dropped in
`V10`, after their one digest is copied into the new table. Kept, they would be a second record of a
fact the table holds, free to disagree with it.

**The history is read with one keyed lookup, not loaded with the aggregate.** The primary key
`(session_id, token_hash)` is the query a refresh makes, so a busy session's history, up to one row per
access-token lifetime for thirty days, is never read whole. The adapter writes and reads it with native
SQL rather than an entity: the rows are written once and never updated, and a byte array is no identifier
JPA can compare.

### 2. Sign-out handles a spent credential exactly as refresh does (S5.A4)

Inside its grace window a spent credential signs out, as before. After it, `end()` calls the same
`revokeForReuse` that `exchange()` calls: the session is revoked and the attempt is audited as
`AUTH_TOKEN_REUSE`, and not also as a refused sign-out. The answer on the wire is unchanged: 204, cookie
cleared.

### 3. A refresh re-reads the account (S5.A9)

A refresh whose credential is current reads the account through `UserQuery` before it rotates. If the
account is switched off, or gone, the refresh is refused, audited as a refused `AUTH_REFRESH` naming the
account and the session, and every session the account holds is revoked, which publishes
`UserSessionsEnded` and closes their sockets. The answer is `Rejected`, which clears the cookie.

### 4. The cookie is `__Secure-refresh_token`, and two of them are refused (S5.A8)

**The name carries the `__Secure-` prefix.** A browser accepts a cookie of that name only with `Secure`
and from a secure origin, so no plain-HTTP host and no network attacker can plant one.
`ShippedRefreshCookieConfigurationTest` pins the name the jar ships.

**A request carrying two is answered as one carrying none.** A sibling host served over HTTPS can still
set a `__Secure-` cookie with `Domain` on the parent, and the browser then sends both with nothing to
say which is whose. `RefreshCookies.read` therefore reads exactly one or nothing: a refresh with two is
refused 401 and a sign-out with two is answered 204, both clearing ours, and neither reads a session.
As with no cookie at all, nothing is audited, because no credential the server could attribute was
presented.

### 5. Refresh and sign-out have a rate limit of their own (S5.A11)

A second `FixedWindowRateLimiter`, with its own map, counts refresh and sign-out together per client
address: sixty a minute by default (`app.session-rate-limit`). It is the same in-process fixed window
ADR-017 chose, registered by `RateLimitConfig` as a second interceptor in front of those two paths, so a
refused attempt reads no session and writes no audit entry. The limit is looser than sign-in's ten,
because a signed-in browser calls these endpoints on its own: every tab renews every fifteen minutes and
on each page load, and a household shares one address. It is a separate count so that neither allowance
spends the other: a mistyped password must not cost anybody a renewal, and a busy tab must not cost
anybody a sign-in.

### 6. A token must name its expiry (S5.A14)

`JwtTokenProvider.verify` refuses a token whose claims carry no `exp`, as it refuses one that names no
session. Every token this application issues has one, so nothing it minted is affected.

### 7. The lifetimes are bounded at startup (S5.A13)

Each is a bean-validation constraint on its properties record, so a value outside it refuses the boot
with the property named, as a missing one already did. Each bound is a named constant beside its reason:

| Setting | Shipped | Bounds | Why |
|---|---|---|---|
| `app.jwt.access-token-ttl` | 15m | 1m to 1h | Shorter renews every tab too often for NFR-58's limit; longer makes a leaked token worth hours |
| `app.auth.session.idle` | 7d | at least 1h, and at most `absolute` | Shorter than the longest token lets an active session lapse between renewals; longer than the cap does nothing it says |
| `app.auth.session.absolute` | 30d | 1h to 90d | NFR-47: no session lives indefinitely |
| `app.auth.session.rotation-grace` | 10s | at most 1m | Longer forgives replays; zero is allowed, and forgives nothing |

The bounds admit every value the repository sets anywhere, `AuthSessionFlowIT`'s zero grace included.

### 8. The audit stream names a session by a digest (S5.C3)

`LoggingAuditTrail` writes an `AUTH_SESSION` resource's id as the first sixteen hex characters of the
SHA-256 of the id's text. One session always gives the same value, so its entries still join up, and
anyone who holds a session id, from the database or a support case, finds its entries by computing the
digest. Every other resource's id is written as it is.

## Consequences

**Easy.** Reuse is detected however many times the thief has refreshed, and whichever cookie endpoint the
spent credential is presented to, which is what ADR-015 claimed. A refusal still names the session's
owner only when the credential is one the session issued, and that test now covers every credential it
issued. An account switched off at any moment is signed out at its next refresh at the latest.

**Hard.**

- Every refresh writes a row, and a session that is refreshed every fifteen minutes for thirty days
  accumulates about 2,900 of them before the sweep deletes it. A refusal of a credential that is not
  current costs one more indexed read. The digest lookup is an index comparison, not a constant-time
  one; what it compares is the SHA-256 of the attacker's own input, so its timing reveals nothing about
  any credential's secret.
- Every refresh also reads the account, one indexed read beside the session's.
- Renaming the cookie signs every browser out once: the old `refresh_token` is no longer read, and
  lapses on its own `Max-Age`.
- A deployment that sets a lifetime outside its bounds no longer starts. That is the intent, and
  the error names the property.
- A household with more than sixty renewals and sign-outs a minute behind one address is refused 429
  until the window turns.
- A sibling host that keeps planting a duplicate keeps the victim signed out, because the clearing
  cookie removes only ours. That is a denial of service on one browser, which is the lesser harm than
  a session moved into the attacker's account.

## Alternatives considered

**Keep the previous digest on the row, and add the history beside it.** Rejected: the previous digest is
also the newest row of the history, so the two would have to be kept in step forever, for a fast path
that saves one indexed read on the rare stale refresh. **Revisit** if a profile ever shows that read.

**Map the history as an `@ElementCollection` on `AuthSession`.** Rejected: adding to a collection makes
Hibernate load all of it first, so every refresh would read the session's whole history to append one
row. **Revisit** if the history is ever bounded to a handful of rows.

**Treat any credential older than the previous one as reuse, whatever its age.** Rejected: it revokes a
session whose two rotations both happened inside one grace window, which is two tabs racing, not theft.

**Keep refusing a spent credential at sign-out.** Rejected: the refusal was added so sign-out could not
hide reuse as an ordinary sign-out, and it did that, but it left the session to the thief, which is the
outcome reuse detection exists to prevent.

**Check the account in `AuthService.continueSession`, after the rotation.** Rejected: the rotation would
already be committed, so the refusal would have to undo it, and the session would have issued one more
credential to an account that may no longer sign in.

**Leave the session id out of the audit line altogether.** Rejected: an investigation of a replay
needs every entry about one session, and the trace id joins an entry to a request, not to the other
requests of the same session.

**An HMAC of the session id, under a key of its own.** Rejected for now: it would also stop someone who
holds a session id from confirming it against the stream, but it needs a second secret to manage, and the
digest already gives nothing that can be put in a token. **Revisit** if the audit stream is ever shipped
somewhere readable by people who should not learn which sessions exist.

**Warn about an out-of-range lifetime at startup instead of refusing it.** Rejected: ADR-042 already
made the application refuse to start without a signing key rather than run insecurely, and a warning at
boot is read by nobody once the deployment is up.

**Count refresh and sign-out in the sign-in bucket.** Rejected: ten a minute is far below what a
signed-in household's own tabs ask for, so the honest renewals would spend the allowance a mistyped
password needs, and an attacker could lock an address out of signing in by refreshing.

**Leave the limit to nginx.** Rejected: nginx is the only edge in the compose deployment, not in every
one, and ADR-017 put the sign-in limit in the application for the same reason.

**The `__Host-` prefix.** Rejected: it would also forbid `Domain`, which closes the sibling-host case
outright, but it requires `Path=/`, which sends the long-lived credential with every API call. ADR-015
scoped it to `/api/auth` so it is not. **Revisit** if the API moves to a host of its own, where
`Path=/` costs nothing.
