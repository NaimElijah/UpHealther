# ADR-072: Every migration bounds its lock wait, and an index on a populated table is built concurrently

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/src/main/resources/db/migration/`, from `V10__auth_session_spent_tokens.sql` on.
  - `MigrationLockTimeoutContractTest`, which enforces the first rule, and `FlywayMigrations`, the
    reader it shares with `ColumnBoundContractTest`.
  - No change to the application's configuration: `spring.flyway.*` is left as it is.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D6.16. Sits beside
  [ADR-063](ADR-063-one-schema-with-cross-context-foreign-keys.md), which keeps every context's tables
  in the one schema these migrations change.

## Context

Flyway runs each migration at startup, before the application serves a request. On PostgreSQL it wraps
each one in a transaction of its own, so a migration's DDL takes its locks inside that transaction and
holds them until it commits.

**A DDL statement can stall a table's whole traffic without doing any work.** `ALTER TABLE` and
`CREATE INDEX` take locks that conflict with the ones ordinary queries take: `ACCESS EXCLUSIVE` for most
of `ALTER TABLE`, `SHARE` for a plain `CREATE INDEX`. If a transaction already holds the table, the
statement waits for it. PostgreSQL queues lock requests, and a later request that conflicts with a
waiting one waits behind it. So every query on that table queues behind the migration, which is itself
queued behind the oldest transaction, for as long as that transaction lives. A rolling deploy, which
starts the new version while the old one is still serving, is exactly that state.

**Only `V10` bounded the wait.** It opens with `SET LOCAL lock_timeout = '5s'` (its comment says why).
`V1` to `V9` do not, and neither did any rule.

**There is no production database yet** ([#206](https://github.com/NaimElijah/UpHealther/issues/206)).
The one that is created will start empty, so for now every migration runs against a handful of seeded
rows, and building an index takes no measurable time.

**How Flyway 12 decides a migration's transaction.** Checked in `flyway-core` and
`flyway-database-postgresql` 12.4.0, the versions the build resolves:

- `PostgreSQLParser` marks a statement matching `^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY` as one that
  cannot run in a transaction, and every other statement in these migrations as one that can.
- `ParserSqlScript.executeInTransaction()` runs a script in a transaction only if it found no statement of
  the first kind. A file that holds only `CREATE INDEX CONCURRENTLY` therefore runs outside one.
- With `mixed` at its default of `false`, which nothing here changes, `ParserSqlScript` refuses a file that
  holds both kinds: "Detected both transactional and non-transactional statements within the same
  migration (even though mixed is false)".
- A non-transactional migration that fails is not rolled back. `DbMigrate` records it in
  `flyway_schema_history` as failed, and the next start refuses to run until the record is repaired.

## Decision

1. **Every migration from `V10` on opens with `SET LOCAL lock_timeout`, before its first other statement.**
   `'5s'`, as `V10` chose, unless the migration says why it needs another. A lock that cannot be had in
   that time fails the migration, so the new version does not start and the old one keeps serving,
   rather than both queueing behind it. `LOCAL`, so the setting ends with Flyway's transaction.
   `MigrationLockTimeoutContractTest` fails the build on a migration that does not open this way, on a
   session-level `SET`, and on a timeout of `0`, which turns the bound off. `V1` to `V9` are exempt:
   Flyway checksums an applied migration, so they cannot gain the line.

   **The bound is per lock request.** It limits each wait for a lock, not how long the migration holds the
   locks it already has. A migration that waits for its second table's lock goes on holding its first
   table's, so traffic on the first queues for that whole wait too, and a migration over several tables
   can wait once per lock. If the old version, still serving during a rolling deploy, takes the same
   tables in the other order, as a delete cascading from one table into the next does, the two can
   deadlock. So a migration that touches several busy tables takes its locks up front, in one
   `LOCK TABLE` naming them in a fixed order, as V10 does for its one table, or touches one table.
   Several of V11 to V18 touch more than one table without one, which is harmless while the production
   database is empty (#206).
2. **While the production database holds no data, schema and index changes are plain DDL inside Flyway's
   transaction.** `CREATE INDEX`, `DROP INDEX`, `ADD CONSTRAINT` and `VALIDATE CONSTRAINT` on an empty or
   seeded table are instant, and inside a transaction a failure rolls the whole migration back.
   A constraint is still added `NOT VALID` and validated by a second statement. In one transaction that
   gains nothing, because the lock `ADD CONSTRAINT` takes is held to the commit; it is written so that
   rule 3 needs only the `VALIDATE` moved.
3. **Once production holds data, an index built or dropped on a populated table uses `CONCURRENTLY`, in a
   migration file of its own.** `CREATE INDEX CONCURRENTLY` takes `SHARE UPDATE EXCLUSIVE`, which no read
   or write conflicts with, so traffic carries on while it builds. It cannot run in a transaction, and
   Flyway runs a file of such statements outside one, as the Context shows. That file holds only those
   statements:
   - Flyway refuses `SET LOCAL lock_timeout` beside them, and outside a transaction `SET LOCAL` does
     nothing anyway. The bound is not needed there for the reason it exists: no query's lock conflicts
     with `SHARE UPDATE EXCLUSIVE`, so nothing queues behind the build.
   - So `MigrationLockTimeoutContractTest` refuses such a file today. The change that adds the first one
     exempts a file whose every statement is `CREATE` or `DROP INDEX CONCURRENTLY`, the same test Flyway
     applies.
   - A failed concurrent build leaves an `INVALID` index behind and the migration recorded as failed.
     The fix is to drop the index, repair the history, and start again.

   For the same reason a constraint on a populated table is added `NOT VALID` in one migration and
   validated in the next. `VALIDATE CONSTRAINT` takes `SHARE UPDATE EXCLUSIVE`, so in a transaction of
   its own it scans the rows without blocking writes, while the `ADD` before it is instant.

## Consequences

**What this makes easy**

- **A migration that cannot get its lock fails loudly and early.** The new version refuses to start, the
  old one goes on serving, and the log names the migration and the lock.
- **A reviewer has one thing to check.** The test checks that the bound is there; the reviewer checks
  that a migration on a populated table is not holding `ACCESS EXCLUSIVE` for longer than it must.
- **The rule costs nothing while the database is empty**, and it is already in place when it is not.

**What this makes hard**

- **A migration can fail where it would have succeeded by waiting.** Five seconds behind a long report or
  a stuck session is enough. The start is then retried once the holder is gone, which needs someone to
  notice: the deploy fails rather than the site.
- **Nothing tells a migration on an empty table from one on a populated one.** Rule 3 is a reviewer's
  judgement, not a test's: the test cannot know how many rows production holds.
- **A concurrent build is two migrations' worth of care.** It is not atomic, it can leave an invalid
  index behind, and it needs a file of its own and an exemption in the test the first time.

## Alternatives considered

- **`spring.flyway.init-sqls: SET lock_timeout = '5s'`.** One line of configuration instead of a line in
  every migration. Rejected: Boot hands Flyway the application's `DataSource` unless `spring.flyway.url`
  is set, so Flyway borrows its connections from Hikari's pool. It runs `initSql` on each one as a plain,
  session-level statement, and on returning the connection restores only its role
  (`PostgreSQLConnection.doRestoreOriginalState`). The timeout would stay on that connection after Flyway
  hands it back, and bound the application's own queries on whichever requests drew that connection.
- **A dedicated Flyway connection with its own URL and credentials.** It would keep any session setting out
  of the pool, and it is the shape a separate migrating role needs. Rejected for now:
  [ADR-069](ADR-069-the-compose-backend-connects-as-a-role-that-owns-its-database.md) chose one role and
  rejected a separate migration role until the deployment gets a managed database. A second set of
  credentials is that decision, not one to take to set a timeout.
- **`statement_timeout` instead of, or beside, `lock_timeout`.** It would also bound a migration that is
  slow for another reason, such as a backfill. Rejected: the hazard here is the wait, and a statement
  timeout short enough to matter would fail a legitimate long `VALIDATE` on a large table.
- **`CONCURRENTLY` from now on.** Rejected while there is no data: it gives up the transaction, and with
  it the rollback, to protect traffic that does not exist yet.

## When to revisit

- **The production database holds data.** From then on rule 3 applies, and the first index change on a
  populated table brings its own file and the test's exemption.
- **A migration needs a lock for longer than five seconds' wait allows**, repeatedly. Then the deploy needs
  a maintenance window or a quieter time, not a longer timeout.
- **The migrations move to a role or connection of their own**, as ADR-069 expects with a managed database. Then a
  session-level setting no longer reaches the pool, and `init-sqls` becomes a valid replacement for the
  line in every file.
- **Flyway changes how it classifies a statement**, which an upgrade past 12.x could do. Re-read
  `PostgreSQLParser` before relying on rule 3.
