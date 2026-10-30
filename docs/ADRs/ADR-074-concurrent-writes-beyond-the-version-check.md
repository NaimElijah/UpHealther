# ADR-074: Concurrent writes beyond the version check

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `TrackingConfigRepositoryAdapter.save`, and the contract of `TrackingConfigRepositoryPort.save`.
  - NFR-7 in `docs/requirements/requirements.md`.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D6.6. Builds on BR-14's
  optimistic lock, which this record leaves as it is.

## Context

BR-14's `@Version` on `HealthUpgrade` refuses two saves of **the same row** that overlap. Some
concurrent writes never touch the same row twice, so the version check cannot see them.

**A check-then-insert against a unique constraint.** `TrackingService.saveConfig` looks up the
upgrade's tracking configuration and, finding none, inserts one. `tracking_configs.upgrade_id` is
`UNIQUE` (constraint `tracking_configs_upgrade_id_key`), so of two first `PUT`s that both find none,
the database refuses the second insert. Nothing translated that refusal: the save was not flushed, so
it surfaced at commit, after the service had returned, and the catch-all handler answered 500. NFR-7
allows a 500 only for a genuine server fault, and this one is a request that would succeed if sent
again a moment later.

Registration met the same shape first. `UserRepositoryAdapter.saveAndFlush` flushes, and translates
`users_email_key`, by name, into `EmailAlreadyRegisteredException` (FR-4, `RegistrationRaceIT`).

## Decision

**A check-then-insert race against a unique constraint is settled by the constraint, and the
persistence adapter translates the refusal into the conflict the API already answers.**

1. **The adapter's save flushes**, so the constraint refuses the loser inside the call, where the
   adapter can translate it, rather than at commit.
2. **It translates that constraint alone, by name.** Any other integrity failure is a defect, and is
   rethrown unchanged, as `UserRepositoryAdapter.saveAndFlush` does.
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

- **No new lock, and no window.** The unique constraint is already there and is already the final
  word, so the fix adds no locking and does not narrow the race; it makes the loser's answer right.
- **The status is honest.** The loser is answered 409 with a message that says to retry, and the audit
  trail records the attempt as refused (`AuditOutcome` classifies `RetryableConflictException`).
- **One precedent, followed twice.** A third table with the same race takes the same three lines.

**What this makes hard**

- **The constraint's name is a contract.** It is PostgreSQL's generated name for a column-level
  `UNIQUE` in `V1__init_schema.sql`. A migration that renames or recreates the constraint must keep the
  name, or the translation stops matching and the race is a 500 again. `TrackingConfigPersistenceIT`
  fails if it does.
- **Every save flushes.** A transaction that saved the configuration and then wrote more would issue the
  INSERT earlier than before. `saveConfig` writes nothing after it, so nothing changes today.
- **The loser's transaction is rolled back.** Spring marks it rollback-only when the violation passes
  through the repository proxy, so the translation can only report the conflict, never recover from it
  inside the same request.

## Alternatives considered

- **Retry inside the server.** The adapter, or the service, would catch the violation, re-read the
  winner's row and update it. Rejected: the transaction is already rollback-only, so the retry would need
  a transaction of its own, and the client can resend the same request with nothing lost.
- **`INSERT ... ON CONFLICT (upgrade_id) DO UPDATE`.** The race would disappear, with no 409. Rejected:
  it is a native statement beside a JPA entity, so Hibernate's persistence context would not hold the
  result, and the service would read back what it just wrote. The race is rare enough that a retryable
  409 costs the client nothing.
- **A lock before the lookup.** Serialising configurations of one upgrade with a row or advisory lock.
  Rejected: the unique constraint already decides, so a lock would only move the loser's wait, and it
  adds a second mechanism for one invariant.
- **`ConflictException` (a context subclass), a plain 409.** Rejected for the reason in Decision 3: it
  tells the client to resolve something, when resending is all it takes.

## When to revisit

- **A client is seen resending the same first configuration in a loop**, which would mean the 409 is
  not resolving, or the race is not rare: `ON CONFLICT` then earns its cost.
- **A migration touches `tracking_configs.upgrade_id`'s constraint.** Its name must survive, or this
  translation must change with it.
- **A second database engine, or a store without unique constraints.** The decision rests on the
  database refusing the duplicate.
