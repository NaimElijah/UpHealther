# ADR-021: What a refused authentication names, and what is no attempt at all

- **Status:** Accepted
- **Date:** 2026-10-05
- **Scope:** the audit entries written by `AuthSessionService` (refresh and sign-out),
  `JwtChannelInterceptor` (STOMP CONNECT) and `AdminBootstrapRunner`, and the two actions they added,
  `auth.connect` and `admin.bootstrap`. Extends [ADR-011](ADR-011-audit-as-a-log-stream.md); no new
  dependency, no migration.
- **Issue:** [#98](https://github.com/NaimElijah/UpHealther/issues/98), and the review of #132

## Context

NFR-23 says every authentication outcome is audited, refusals included. Until #98 only a sign-in
(allowed or refused), a successful refresh, a detected replay and a successful sign-out were. A refused
refresh, a refused sign-out, every STOMP CONNECT and the bootstrap promotion of the first
administrator left nothing.

ADR-011 settled how an entry is written and what it may hold: two enums and two identifiers, never
free text. On *whom* an entry names it said one thing: **a refused login is recorded with no
subject at all**, because the submitted email is personal data and naming a user id would confirm the
account exists.

Closing #98 meant answering the same question for credentials that are not an email and a password:

- **A refresh credential is `<sessionId>.<secret>`.** The session id is no secret: it travels in an
  access-token claim. So a credential can name a real session and still prove nothing about who sent
  it.
- **Sessions expire, and the nightly sweep deletes the row.** An entry that names only the session can
  no longer be traced to anyone the next day.
- **The client sends requests that present nothing:**
  - every signed-out page load calls `/api/auth/refresh` once, with no cookie;
  - a tab whose session can no longer be renewed connects over STOMP with no `Authorization` header,
    every five seconds, for as long as it stays open.
- **Some checks authenticate nothing new.** Every API request re-checks its access token. Every
  SUBSCRIBE or SEND on an open socket is authorised against that socket's principal.

## Decision

**A refusal names the session whenever it exists, and its owner only when the credential is the
session's own.**

- The owner is named when the presented credential is one the session issued, current or superseded.
  That records *whose credential it was*, not who sent it. A superseded credential presented after the
  grace window has probably been stolen, and it is named the same way the existing `AUTH_TOKEN_REUSE`
  entry names it.
- A credential the session never issued names the session and nobody.
- One that is unparseable, or names no session, names nothing.

**A CONNECT is audited like a sign-in, as `auth.connect`.** The socket stays signed in as that user for
as long as it is open.

- An accepted CONNECT names the user.
- A refused CONNECT names nobody, for the reason ADR-011 gives for a refused login.
- An authenticator that throws is FAILED, not REFUSED.

**A request that presents no credential is no attempt, and is not audited.** This covers a refresh or a
sign-out with no cookie, and a CONNECT with no `Authorization` header. A header that is present but is
not a bearer token did present something, and is audited as REFUSED.

**Re-checking an established credential is not an authentication outcome, and is not audited.** This
covers the per-request check of an access token (an HTTP 401 from the filter), and a refused SUBSCRIBE
or SEND on an open socket, which stays a WARN line. The owner decided both on #98.

**A Stale refresh is REFUSED.** Its retry may well succeed, but this request was not honoured.

**The bootstrap promotion is `admin.bootstrap`, with no actor.** No person granted the role; the
deployment did. `admin.role` means an administrator acting on somebody else's account. A disabled
account is not promoted and is not recorded, because it would administer nothing.

## Consequences

**What this makes easy.**

- **The commonest refusal stays attributable:** a session that lapsed after a week away still names
  its owner after the sweep has deleted its row.
- **Nobody can put attempts against another person's name** just by knowing a session id.
- **The trail's volume follows what people do**, not what clients do on their own. A signed-out visit
  or a dead tab adds nothing.

**What this makes hard.**

- **The trail cannot say who probed a session id.** Like a refused login, such a refusal is a rate
  signal, not an attribution.
- **A stolen credential's refusals are recorded under the victim's id.** The entry says whose
  credential it was. Telling owner from thief takes the trace id and the request, not the entry.
- **`auth.connect` REFUSED is ordinary traffic:** a client reconnecting with a lapsed token. It is not
  a signal to alert on.
- **An outage that stops a transaction from starting is still not audited.** That is a property of how
  every use case records, not of this decision, and it is tracked as
  [#133](https://github.com/NaimElijah/UpHealther/issues/133).

## Alternatives considered

**Name the owner on every refusal of an existing session.** This is simple, and it is what
`AUTH_TOKEN_REUSE` already does for the one case where it applies. It was rejected because the session
id is public: anybody holding one could fill the trail with refusals under its owner's name.

**Name nobody on any refusal, as a refused login does.** This is the most private option. It was
rejected because an expired session is the commonest refusal, and once the sweep deletes the row, an
entry with no owner is unattributable. A credential the session issued does not reveal anything the
caller did not already hold.

**Audit a request with no credential as REFUSED.** This was rejected on volume. It would add one
entry per signed-out page load, and one every five seconds per dead tab — about seventeen thousand a
day per idle tab. That would bury the refusals worth reading.
- **Revisit when** anonymous probing needs counting, for rate limiting or an alert. The answer then is
  an unlabelled counter, not audit entries.

**Audit every per-request token check.** This was rejected because it would make the trail one entry
per API request: an access log, not an audit trail.
- **Revisit when** a compliance obligation asks for per-request access records. That would also
  revisit ADR-011's choice of a log stream.

**Audit a refused SUBSCRIBE or SEND.** This was rejected because these are authorisation decisions on
a connection that is already authenticated, and a correct client never sends either. The WARN line is
enough to see one.
- **Revisit when** probing of destinations is actually observed, or wanted as an alert. It would then
  get its own action rather than share `auth.connect`.

**Reuse `admin.role` for the bootstrap, with a null actor.** This was rejected because it would mix a
grant made by configuration into an action that means an administrator acted. A query for
administrators' actions would then return one that no administrator took.
