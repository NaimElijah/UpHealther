# ADR-063: One schema, with foreign keys across the contexts

- **Status:** Accepted — extended by
  [ADR-073](ADR-073-the-schema-refuses-what-the-domain-cannot-hold.md) (progress entries, reflections and an
  upgrade's area reference their parent by id and owner together, so a child row's owner is its parent's)
- **Date:** 2026-10-30
- **Scope:**
  - `backend/src/main/resources/db/migration/` — `V1__init_schema.sql`, `V4__notifications.sql` and
    `V9__auth_sessions.sql`, whose foreign keys are the decision.
  - FR-8, FR-14, BR-13 and NFR-8 in `docs/requirements/requirements.md`.
  - A record of a decision already in the code. No change to the schema, the API or the tests.
- **Issue:** [#338](https://github.com/NaimElijah/UpHealther/issues/338), scan 04 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding A4.B7. It sits beside
  [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md), which set out
  what the bounded contexts may import from one another; this record covers what the database
  connects, which no import shows.

## Context

The backend is ten bounded contexts, and `HexagonalArchitectureTest` keeps the graph between them
acyclic and narrow. `upgrade` imports only `healtharea`. It has no edge to `tracking`, `reminder` or
`reflection`, and `UpgradeService.delete` publishes no event.

Yet deleting an upgrade deletes their rows. FR-14 requires it: an upgrade goes with its tracking
configuration, progress, reminders and reflections, and its notifications stay, detached from it. BR-13
says reflections go only that way. `UpgradePersistenceIT` deletes one upgrade and checks that the four
tables are emptied of its rows and that its notification remains, detached.

The path is the schema, not the code:

- **One schema.** Every context's tables are in the one PostgreSQL schema, built by a single Flyway
  history, `V1` to `V9`. No migration creates a second schema.
- **Entities hold ids, not relations.** No entity maps `@ManyToOne` or `@OneToMany`, and none declares a
  JPA cascade. A `tracking_configs` row names its upgrade by a `UUID` column, as every other table does.
- **The foreign keys carry the rules.** Each rule is declared once, in SQL:

  | Foreign key | `ON DELETE` | What it carries out |
  |---|---|---|
  | `tracking_configs`, `progress_entries`, `reminders`, `reflections` → `health_upgrades(id)` | `CASCADE` | FR-14, BR-13: an upgrade takes its records with it |
  | `notifications.related_upgrade_id` → `health_upgrades(id)` | `SET NULL` | FR-14: a notification outlives its upgrade, detached |
  | `health_upgrades.area_id` → `health_areas(id)` | `SET NULL` | FR-8: deleting an area leaves its upgrades intact |
  | `health_areas`, `health_upgrades`, `progress_entries`, `reflections`, `notifications`, `auth_sessions` → `users(id)` | `CASCADE` | Every owned row goes with its account |

  No use case deletes an account today, so the last row is a property of the schema that
  `AuthSessionPersistenceIT` checks for sessions, and not a feature.

Nothing in the Java shows this. A reader of the import graph, or of the context map, has no way to learn
that the rule exists.

## Decision

**The contexts share one PostgreSQL schema, and a delete that crosses a context boundary is carried out
by an `ON DELETE` clause on a foreign key, not by an event or a call.**

1. **One schema, one Flyway history.** A new context's tables join it.
2. **A table that references `health_upgrades` or `users` states its `ON DELETE` outright.** The rule it
   carries is a requirement: `CASCADE` when the row has no meaning without its parent, `SET NULL` when
   it outlives it. The default, `NO ACTION`, would make the delete fail.
3. **The referencing column is indexed.** A `REFERENCES` clause creates no index, and on every parent
   delete PostgreSQL's referential-integrity trigger scans the child table for the rows to cascade or
   null. Without an index that is a sequential scan of a table that only grows.
   `notifications.related_upgrade_id` has none today. Measured in the review of #378, on PostgreSQL 15.19
   with synthetic data, its trigger took 10.7 ms against 0.8 ms for the indexed `progress_entries`.
   [#340](https://github.com/NaimElijah/UpHealther/issues/340) tracks the index.
4. **The deleting context does not learn what went.** `UpgradeService.delete` removes one row and records
   one audit entry. It publishes no event, and no other context observes the delete.
5. **The rule is written down where a maintainer will look.** `architecture.md` names it among the
   structural decisions not visible from the file layout. The ER diagram draws the foreign keys from
   the migrations, without their `ON DELETE` clauses, so this record and the migrations carry those.

## Consequences

**What this makes easy**

- **A delete is atomic.** The upgrade and everything recorded against it go in the one statement's
  transaction, or none does. There is no window in which an upgrade is gone and its progress is not, and
  no listener that can fail after the commit and leave orphans.
- **No event plumbing, and no ordering to get right.** Adding a context that stores data against an
  upgrade costs one `REFERENCES ... ON DELETE CASCADE`, an index on the referencing column, and no
  change to `upgrade`.
- **The import graph stays small.** `upgrade` need not know `tracking`, `reminder` or `reflection`
  exist, which keeps the edge ADR-002 removed removed.
- **The rule is the database's.** A code path that forgets it, or a manual `DELETE`, meets it anyway.

**What this makes hard**

- **Moving a context's tables out is not free.** A foreign key cannot span two databases. Extracting
  `tracking`, `reminder` or `reflection` replaces the cascade with an event the other side must consume,
  idempotently, and that has a failure mode the cascade never had.
- **A context cannot take a soft delete alone.** Marking an upgrade deleted removes no row, so the
  cascade never fires: its children stay, and every query that reads them would need to learn the flag.
- **The rule is invisible in Java.** It is in no entity and no service, and a reader of the code finds
  an `upgrade` with no edge to the contexts whose rows it deletes. This record and `architecture.md`
  carry that cost.
- **A new table can be forgotten.** One that references `health_upgrades` without `ON DELETE CASCADE`
  makes deleting an upgrade fail with a constraint violation. `UpgradePersistenceIT` inserts into the four
  tables that exist, so it will not notice a fifth.
- **`SET NULL` bypasses the aggregate.** The database changes `health_upgrades.area_id` on its own when
  an area goes, so `HealthUpgrade` is not loaded and its `@Version` is not incremented. A request that
  loaded the upgrade before the area was deleted, and saves it after, therefore passes the version check
  and writes the old `area_id` back: `HealthUpgrade` has no `@DynamicUpdate`, so every column is written.
  The stale id violates the foreign key, and the client is answered 500 by the catch-all handler where
  a 409 would be right. [#340](https://github.com/NaimElijah/UpHealther/issues/340) tracks it.
- **A delete leaves no trace in another context.** Nothing can react to an upgrade's removal, and an audit
  entry names only the upgrade.

## Alternatives considered

- **A domain event per delete.** `UpgradeService.delete` would publish `UpgradeDeleted`, and `tracking`,
  `reminder` and `reflection` would each delete their own rows. Rejected:
  - an `AFTER_COMMIT` listener that writes needs `REQUIRES_NEW` (ADR-028), so the children would go in a
    second transaction, and a failure there leaves orphans the cascade cannot produce;
  - a listener in the publisher's transaction is atomic, but then the rule is written three times, in
    three contexts, and the foreign keys must stay to keep the rows consistent anyway;
  - it adds the knowledge the cascade avoids: every context holding upgrade data must have a listener.
- **A schema per context.** Rejected. PostgreSQL allows a foreign key across schemas, so the cascade
  would stay and the coupling with it. The cost would be a schema in Flyway's configuration and on every
  `@Table`, to organise tables that no code reads across.
- **Soft deletes.** An upgrade gets a `deleted_at` column and every read filters on it. Rejected:
  FR-14 asks that the records go, and no requirement asks for an undo. Every query that reads upgrade
  data, the sweeps among them, would need the filter, and one that missed it would show a deleted
  upgrade.
- **JPA cascades.** `HealthUpgrade` would map its configuration, reminders and reflections with
  `cascade = REMOVE`. Rejected: it needs `upgrade` to import `tracking`, `reminder` and `reflection`.
  All three already import `upgrade`, so each import is a cycle, which is the one ADR-002 removed for
  `tracking`, and `bounded_contexts_are_free_of_cycles` fails the build on it.

## When to revisit

- **A context is extracted into a deployable or a database of its own.** The foreign key to
  `health_upgrades` or `users` can no longer exist. That change replaces the cascade with events, and
  supersedes this record for that context.
- **A requirement asks to undo a delete, or to keep deleted records** for retention or audit. Soft
  deletes then change what a delete is, and the cascade with it.
- **A delete needs an effect the database cannot carry out**, such as a call to another service or the
  removal of a stored file. That step needs an event, and the cascade can remain beside it.
- **A deletion needs a trace in another context**, such as a notification that something was removed.
