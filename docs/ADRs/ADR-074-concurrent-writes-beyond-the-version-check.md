# ADR-074: Concurrent writes beyond the version check

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `UpgradeService`'s concurrent-HARD check, `UpgradeRepositoryPort.serialiseHardLimitChecks` and its
    implementation in `UpgradeRepositoryAdapter` and `UpgradeJpaRepository`.
  - `TrackingConfigRepositoryAdapter.save`, and the contract of `TrackingConfigRepositoryPort.save`.
  - BR-5 and NFR-7 in `docs/requirements/requirements.md`.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), findings D6.4 and D6.6. Builds on BR-14's
  optimistic lock, which this record leaves as it is.

## Context

BR-14's `@Version` on `HealthUpgrade` refuses two saves of **the same row** that overlap. Some
concurrent writes never touch the same row twice, so the version check cannot see them. Two shapes
of that exist in this codebase.

**A per-user invariant spanning several rows.** BR-5 allows a user at most three `HARD` upgrades
`ACTIVE` at once. `UpgradeService` counts the user's ACTIVE HARD upgrades, then transitions and saves a
*different* row: the one being activated, or the one being promoted to HARD. Under PostgreSQL's default
READ COMMITTED, two such requests for one user, say from two tabs, both count two, both pass, and both
commit, so the user ends with four. Each row's version is checked and nothing is wrong with either
row. No constraint can say "at most three rows match this predicate" either.

**A check-then-insert against a unique constraint.** `TrackingService.saveConfig` looks up the
upgrade's tracking configuration and, finding none, inserts one. `tracking_configs.upgrade_id` is
`UNIQUE` (constraint `tracking_configs_upgrade_id_key`), so of two first `PUT`s that both find none,
the database refuses the second insert. Nothing translated that refusal: the save was not flushed, so
it surfaced at commit, after the service had returned, and the catch-all handler answered 500. NFR-7
allows a 500 only for a genuine server fault, and this one is a request that would succeed if sent
again a moment later.

Registration met the second shape first. `UserRepositoryAdapter.saveAndFlush` flushes, and translates
`users_email_key`, by name, into `EmailAlreadyRegisteredException` (FR-4, `RegistrationRaceIT`).

## Decision

### 1. A per-user invariant over several rows is serialised by a transaction-scoped advisory lock

**Before it counts, a use case that checks BR-5 takes a PostgreSQL advisory lock keyed on the user,
held until its transaction ends.**

- **Where.** `UpgradeService` calls `UpgradeRepositoryPort.serialiseHardLimitChecks(userId)` immediately
  before `countByUserIdAndStatusAndDifficulty`, on both routes into a running HARD upgrade: activating
  one, and promoting an ACTIVE one. A route that claims no HARD slot neither counts nor locks.
  `UpgradeServiceTest` checks the order on both routes.
- **How.** `UpgradeJpaRepository` runs
  `SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('hard-limit ' || CAST(:userId AS text), 0))`.
  - The `xact` form is released by the commit or rollback, so nothing has to remember to unlock. A
    session-level lock would stay with the pooled connection after the request.
  - The key is a stable 64-bit hash of the user id. The `hard-limit ` prefix keeps it apart from the
    same user's key for any other per-user lock a later invariant takes. A clash with any other
    advisory lock in the database, Flyway's included, is a 64-bit hash collision, which is accepted.
  - The function is called in `FROM` so the statement returns an integer. `pg_advisory_xact_lock`
    returns `void`, which is not a type JPA can read back.
- **It must run inside a transaction.** Outside one, the lock would end with the statement and
  serialise nothing. The repository method is `@Transactional(propagation = MANDATORY)`, so the call
  fails instead, and the port's doc comment states the precondition.
- **The domain and the application see no SQL.** The lock sits behind a port method that says what it
  serialises, and `HexagonalArchitectureTest` keeps the query in the adapter. `HardLimitLockIT` proves
  the lock against the real database:
  - while one transaction holds it for a user, another connection cannot take it;
  - another user's can be taken;
  - it is free again after the commit;
  - the call fails outside a transaction.

A second request for the same user waits at the lock until the first commits. Each statement under
READ COMMITTED reads what had committed when it started, so the second's count then includes the
first's activation.

### 2. A check-then-insert race against a unique constraint is translated in the adapter

**The constraint settles the race, and the persistence adapter translates its refusal into the conflict
the API already answers.** `UserRepositoryAdapter.saveAndFlush` is the precedent.

1. **The adapter's save flushes**, so the constraint refuses the loser inside the call, where the
   adapter can translate it, rather than at commit.
2. **It translates that constraint alone, by name.** Any other integrity failure is a defect, and is
   rethrown unchanged.
3. **The translation is the exception whose meaning fits.** For the tracking configuration it is the
   kernel's `RetryableConflictException`, a 409: the request was right and was overtaken, and resent
   unchanged it finds the winner's configuration and replaces it, which is what a `PUT` means.
   `ConflictException` is the wrong one here: it says that something in place stands in the request's
   way and the client has to resolve it, as with a second progress entry for a day.
4. **The port says so.** `TrackingConfigRepositoryPort.save` documents the flush and the exception, so a
   caller learns it from the contract rather than from the adapter.

The progress entry's race (`progress_entries`, one per upgrade and day) has the same shape and is
deliberately not part of this change. [#146](https://github.com/NaimElijah/UpHealther/issues/146)
tracks it.

## Consequences

**What this makes easy**

- **BR-5 holds under concurrency, at the cost of one statement.** The lock is taken only on a route that
  counts, and it holds up only requests for the same user. Two tabs see one activation succeed and the
  other refused with the ordinary 422, never four HARD upgrades.
- **No schema change for either.** The advisory lock needs no table, and the unique constraint was
  already the final word on the tracking configuration, so neither fix needs a migration.
- **The status is honest.** The tracking configuration's loser is answered 409 with a message that says
  to retry, and the audit trail records the attempt as refused (`AuditOutcome` classifies
  `RetryableConflictException`).
- **One pattern per shape.** A new invariant over a user's rows takes a lock of its own behind a port
  method. A new table with a check-then-insert race takes the same three lines in its adapter.

**What this makes hard**

- **The lock ties the code to PostgreSQL.** `pg_advisory_xact_lock` and `hashtextextended` exist nowhere
  else. The query is confined to one adapter, but replacing the database means replacing the mechanism,
  not just the statement.
- **A second request for the same user waits** for as long as the first one's transaction lasts. Every
  transaction that takes the lock is a single short request today. One that held it across a slow call
  would hold up that user's other activations.
- **A new route into a running HARD upgrade must take the lock.** Nothing enforces that beyond the two
  ordered tests on the routes that exist, and `backend/CLAUDE.md` says so.
- **The constraint's name is a contract.** `tracking_configs_upgrade_id_key` is PostgreSQL's generated
  name for a column-level `UNIQUE` in `V1__init_schema.sql`. A migration that renames or recreates the
  constraint must keep the name, or the translation stops matching and the race is a 500 again.
  `TrackingConfigPersistenceIT` fails if it does.
- **Every tracking-configuration save flushes.** A transaction that wrote more after the save would see
  the INSERT issued earlier than before. `saveConfig` writes nothing after it, so nothing changes today.
- **The loser's transaction is rolled back.** Spring marks it rollback-only when the violation passes
  through the repository proxy, so the translation can only report the conflict, never recover from it
  inside the same request.

## Alternatives considered

**For the per-user invariant (1):**

- **`SELECT ... FOR UPDATE` on the user's ACTIVE HARD rows.** Rejected: it locks the rows that exist, and
  the race is about a row that is not yet ACTIVE HARD. Two requests that each lock the same two rows
  still both see two, and a user with none has nothing to lock.
- **Lock the user's `users` row (`FOR UPDATE`).** It would serialise the same requests, and is portable
  SQL. Rejected: the `upgrade` context would query another context's table to lock a row it does not
  own, and every other write to that account, an administrator's included, would queue behind an
  activation.
- **`SERIALIZABLE` isolation for the two use cases.** PostgreSQL would detect the write skew and abort one
  transaction. Rejected: the loser fails with a serialization error that needs a retry loop and a status
  of its own, and the isolation level becomes a property each new route has to remember.
- **A counter column with a `CHECK (active_hard <= 3)`.** Rejected: it duplicates state that must be kept
  in step on every transition, the pause, completion and abandonment that free a slot included, and it
  needs a migration.
- **A trigger that counts on UPDATE.** Rejected: the rule would move out of `UpgradeSchedulingService`,
  where the domain states it, into SQL. The trigger would also need a lock of its own to see concurrent
  transactions.

**For the check-then-insert race (2):**

- **Retry inside the server.** The adapter, or the service, would catch the violation, re-read the
  winner's row and update it. Rejected: the transaction is already rollback-only, so the retry would need
  a transaction of its own, and the client can resend the same request with nothing lost.
- **`INSERT ... ON CONFLICT (upgrade_id) DO UPDATE`.** The race would disappear, with no 409. Rejected:
  it is a native statement beside a JPA entity, so Hibernate's persistence context would not hold the
  result, and the service would read back what it just wrote. The race is rare enough that a retryable
  409 costs the client nothing.
- **A lock before the lookup**, as in (1). Rejected: the unique constraint already decides, so a lock
  would only move the loser's wait, and it adds a second mechanism for one invariant.
- **`ConflictException` (a context subclass), a plain 409.** Rejected for the reason in Decision 2.3: it
  tells the client to resolve something, when resending is all it takes.

## When to revisit

- **A second database engine, or a move to a sharded or distributed store.** Advisory locks are
  PostgreSQL's and are local to one server, so (1) has to be redesigned, as a lock row or a
  serializable transaction. (2) rests on the store refusing the duplicate, which a store without unique
  constraints would not.
- **A transaction that takes the HARD-limit lock starts doing slow work**, such as a call to another
  service, or the lock shows up in wait metrics. Then the waits are no longer negligible and (1) needs a
  narrower critical section.
- **A third route into a running HARD upgrade, or a second per-user invariant over several rows.** Each
  takes the lock the same way. A second invariant takes a key of its own.
- **A client is seen resending the same first configuration in a loop**, which would mean the 409 is not
  resolving the race, or the race is not rare: `ON CONFLICT` then earns its cost.
- **A migration touches `tracking_configs.upgrade_id`'s constraint.** Its name must survive, or the
  translation must change with it.
