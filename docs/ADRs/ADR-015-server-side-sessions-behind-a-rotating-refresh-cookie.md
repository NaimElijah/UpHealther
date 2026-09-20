# ADR-015: Server-side sessions behind a rotating refresh cookie, with reuse detection

- **Status:** Accepted
- **Date:** 2026-09-20
- **Scope:** `backend/` — a new aggregate in `auth`, a new table, two new endpoints, and a session check
  on the path of every authenticated request. Extends [ADR-014](ADR-014-unauthenticated-requests-are-401-with-the-api-error-body.md),
  which made a refused request answer 401 so a client could react to one. No new dependency.

## Context

Authentication was a bearer JWT, valid for 24 hours, held in `localStorage`. Three things followed from
that, and all three are the same fact said differently: **the server had no idea who was signed in.**

- **Nothing could be signed out.** A token is valid until it expires. "Log out" cleared browser storage
  and hoped, and a token copied before that point kept working for the rest of the day.
- **Nothing could be revoked.** Deleting or disabling an account could not take effect until the token
  lapsed — the reason NFR-5 re-reads the account on every request is that it was the only revocation
  available.
- **The credential was readable by script.** Anything injected into the page could read `localStorage`
  and walk away with 24 hours of access, on any device, with nothing to detect and nothing to revoke.

Shortening the token's life would trade one problem for another: a 15-minute token that cannot be
renewed is a sign-in every 15 minutes. Something has to survive longer than the token, and whatever
survives is the thing worth stealing.

## Decision

**A short access token in memory, and a long opaque credential in a cookie script cannot read.** The
access token stays a JWT, carries a new `sid` claim naming its session, and is never written to web
storage. Beside it, an opaque refresh credential lives in an `HttpOnly; Secure; SameSite=Strict` cookie
scoped to `/api/auth`, so it is not even attached to ordinary API calls.

**Sessions are rows.** `auth` gains its first aggregate, `AuthSession` — the context previously
orchestrated over `user` and owned nothing. A row carries the digest of the credential it accepts, the
digest of the one it replaced, an idle expiry that slides on each refresh, and an absolute cap that
never moves. Only SHA-256 digests are stored, compared with `MessageDigest.isEqual`, so a copy of the
table yields nothing presentable and a comparison leaks nothing in its timing.

**Every refresh rotates the credential.** A credential is good for exactly one exchange. That is what
makes theft detectable at all: if both the thief and the real client use it, the second one is
presenting something already spent.

**A spent credential inside a ten-second grace window is stale; outside it, it is theft.** Two tabs
waking together, or a request retried after a dropped connection, legitimately present the same
credential within a moment — answering 401 there would sign people out for having a second tab open. So
inside the window the answer is **409, retry**, and nothing is revoked. Outside it the session is
revoked, audited as `AUTH_TOKEN_REUSE`, and logged at WARN with ids only. The real user is signed out
too; that is the cost of the only signal available that a credential has been copied.

**A credential neither digest recognises revokes nothing.** The session id travels in a token claim, so
it is not a secret. If a wrong secret ended the session, anyone who learned an id could sign its owner
out at will.

**Refresh returns a verdict, it does not throw.** `RefreshOutcome` is a sealed `Rotated`/`Stale`/`Rejected`,
and the controller turns it into a status. Throwing from inside the transaction would roll back the
revocation that reuse detection had just decided — the one case where it matters most is the one case
where it would silently not happen.

**Sign-out is per device.** It ends the session the credential belongs to and no other, so signing out
of a browser does not sign the same account out of a phone.

**Two things defend the cookie endpoints.** `SameSite=Strict` means the cookie is not sent cross-site at
all; a required `X-Requested-With` header means a cross-site form post — which cannot set a header —
is refused with 400 before any session is read. Spring's CSRF token machinery is not used; see below.

**The session is checked on every authenticated request.** `BearerTokenAuthenticator` asks
`SessionStatusPort` whether the `sid` is still live. The port is declared in `common` and implemented by
`auth.adapter.in.composition.SessionStatusAdapter`, because the security adapter lives in the shared
kernel and a direct call would point the kernel at a bounded context — the same inversion as
`UpgradeTrackingSummaryPort`.

## Consequences

**Easy.** Signing out means something, on this device and now. Revoking an account's access is a row
update rather than a wait. Injected script cannot reach the long-lived credential. A stolen credential
is usable at most once, and using it is detected.

**Hard.** Every authenticated request now costs a session lookup on top of the account lookup NFR-5
already required — two reads before any handler runs. Refresh takes a pessimistic row lock, so two
refreshes of one session serialise; that is correct, and it means a slow database turns concurrent
refreshes into a queue. The `auth` context now owns state, so "auth owns no aggregate" is no longer
true anywhere it was written down. And reuse detection has a false-positive mode with a real cost: a
client that manages to replay its own credential outside the grace window signs its user out.

**A known gap, deliberately left.** A live STOMP socket outlives sign-out until it reconnects, because
the session is checked when a frame arrives and a connected socket sends none. It only ever receives its
own user's notifications, so the exposure is bounded to that user's own data. Filed as its own issue
rather than fixed here.

## Alternatives considered

**Spring Security's OAuth2 resource-server starter.** Rejected: it solves token *validation*, which is
already done and is not the problem. Sessions, rotation and reuse detection would still have to be
written, and the starter brings an opinionated filter chain for a token format this application mints
itself. **Revisit** if an external identity provider is ever introduced, at which point validating
somebody else's tokens becomes the actual requirement.

**Spring Security's CSRF double-submit token.** Rejected: it needs a cookie readable by script, plus a
token endpoint and a place for the SPA to keep it — machinery whose whole job is to prove the request
came from script, which the required header already proves given `SameSite=Strict`. **Revisit** if the
API ever has to accept a cross-site request, or if a supported browser drops `SameSite` enforcement.

**A reuse *interval* instead of a grace window** — accepting the old credential repeatedly for N
seconds. Rejected: it widens the window in which a stolen credential works from one use to many, and
buys nothing over answering 409 and letting the client retry. **Revisit** never; if tabs still collide,
the fix is the client's single-flight refresh, not a longer amnesty.

**Asymmetric signing keys (RS256/ES256).** Rejected: the only thing verifying these tokens is the
service that issued them, so a public key has no one to be public to, and key management would be real
work for no reader. **Revisit** when a second service has to verify a token without holding the secret.

**Storing the refresh credential in `localStorage` beside the access token.** Rejected outright: it
would put the long-lived credential exactly where script can read it, which is the problem this decision
exists to solve.

**A `@Version` column instead of a pessimistic lock.** Rejected: an optimistic clash surfaces at commit,
outside any handler, as an unmapped 500. The lock makes concurrent refreshes wait, which is what they
should do. **Revisit** if lock contention ever shows up in a latency profile.

**Global sign-out** (ending every session an account holds when it signs out anywhere). Rejected as a
default: signing out of a borrowed laptop should not sign you out of your phone. The capability exists
(`revokeAllForUser`) and is used where it belongs — when an administrator disables an account (ADR-016).
