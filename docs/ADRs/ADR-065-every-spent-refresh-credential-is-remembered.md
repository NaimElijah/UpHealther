# ADR-065: Every spent refresh credential is remembered, and its reuse is handled the same everywhere

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:** `backend/` — `AuthSession`, `SpentCredential`, `AuthSessionService`,
  `AuthSessionRepositoryPort` and its adapter, and migration `V10__auth_session_spent_tokens.sql`;
  NFR-36 in `docs/requirements/requirements.md`.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), scan 05 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334): findings S5.A1 and S5.A4. It extends
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), whose reuse detection it
  makes hold for any number of rotations.

## Context

ADR-015 promises that a stolen refresh credential is usable at most once, and that using it is detected.
Its session row kept the digest of the current credential and of the one before it, and nothing older.
So the promise held for one rotation only:

1. A thief holding C1 refreshes C1 → C2 → C3.
2. The victim presents C1. It matches neither digest, so it is refused as a guess: a 401, no revocation,
   no WARN, no `AUTH_TOKEN_REUSE`. The victim's tab is signed out.
3. The thief's session lives on, up to the thirty-day cap.

A guess has to stay harmless, because the session id is no secret: a wrong secret must revoke nothing.
What was missing was the means to tell a credential the session spent two rotations ago from one it
never issued.

## Decision

**A session remembers every credential it has spent.** Each rotation retires the current digest as a
`SpentCredential`, its digest and the instant it was retired, and the service stores it in
`auth_session_spent_tokens` within the rotation's transaction, under the session's row lock. The rows
cascade with their session, so the nightly sweep and an account's deletion take them too, and a
session's history never outlives it.

**The grace window runs from the moment each credential was spent.** A spent credential presented within
`rotationGrace` of its own retirement is stale (409, retry); after it, the session is revoked as reuse,
with the same WARN, `AUTH_TOKEN_REUSE` entry and published end as before. Two quick rotations therefore
do not turn a request that was already in flight into a theft.

**Sign-out handles a spent credential exactly as refresh does, through one method.** Inside its grace
window it signs out, as before. After it, `end()` calls the same `revokeForReuse` that `exchange()`
calls: the session is revoked and the attempt is audited as `AUTH_TOKEN_REUSE`, and not also as a refused
sign-out. Until now sign-out refused it and left the session alive, so a victim signing out with a
credential the thief had rotated was answered 204, saw themselves signed out, and left the thief signed
in, with nothing recording the reuse (S5.A4). The answer on the wire is unchanged: 204, cookie cleared.

**The aggregate no longer holds a previous digest.** `previous_token_hash` and `rotated_at` are dropped in
`V10`, after their one digest is copied into the new table. Kept, they would be a second record of a
fact the table holds, free to disagree with it.

**The history is read with one keyed lookup, not loaded with the aggregate.** The primary key
`(session_id, token_hash)` is the query a refresh makes, so a busy session's history, up to one row per
access-token lifetime for thirty days, is never read whole. The adapter writes and reads it with native
SQL rather than an entity: the rows are written once and never updated, and a byte array is no identifier
JPA can compare.

## Consequences

**Easy.** Reuse is detected however many times the thief has refreshed, which is what ADR-015 claimed.
A refusal still names the session's owner only when the credential is one the session issued, and that
test now covers every credential it issued.

**Hard.** Every refresh writes a row, and a session that is refreshed every fifteen minutes for thirty
days accumulates about 2,900 of them before the sweep deletes it. A refusal of a credential that is not
current costs one more indexed read. The digest lookup is an index comparison, not a constant-time one;
what it compares is the SHA-256 of the attacker's own input, so its timing reveals nothing about any
credential's secret.

## Alternatives considered

**Keep the previous digest on the row, and add the history beside it.** Rejected: the previous digest is
also the newest row of the history, so the two would have to be kept in step forever, for a fast path
that saves one indexed read on the rare stale refresh. **Revisit** if a profile ever shows that read.

**Map the history as an `@ElementCollection` on `AuthSession`.** Rejected: adding to a collection makes
Hibernate load all of it first, so every refresh would read the session's whole history to append one
row. **Revisit** if the history is ever bounded to a handful of rows.

**Keep refusing a spent credential at sign-out.** Rejected: the refusal was added so sign-out could not
hide reuse as an ordinary sign-out, and it did that, but it left the session to the thief, which is the
outcome reuse detection exists to prevent.

**Treat any credential older than the previous one as reuse, whatever its age.** Rejected: it revokes a
session whose two rotations both happened inside one grace window, which is two tabs racing, not theft.
