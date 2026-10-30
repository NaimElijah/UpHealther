# ADR-073: The schema refuses what the domain cannot hold

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/src/main/resources/db/migration/V12__value_domains.sql` and
    `V13__child_rows_share_their_parents_owner.sql`.
  - `Reminder`, whose two columns become `@Column(nullable = false)`.
  - `EnumColumnCheckContractTest`, `ValueDomainConstraintIT` and `OwnerConsistencyIT`, which enforce it.
  - BR-15, BR-18, BR-19 and BR-20 in `docs/requirements/requirements.md`.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), findings D6.10 and D6.11. Generalises the
  policy `V8__user_role_and_enabled.sql` stated for one column, extends
  [ADR-063](ADR-063-one-schema-with-cross-context-foreign-keys.md), whose foreign keys the owner keys
  replace, and follows [ADR-072](ADR-072-every-migration-bounds-its-lock-wait.md) for how its constraints
  are added.

## Context

The application refuses a bad value at its boundary. An enum field cannot deserialise to a name the enum
lacks, `@Min(1) @Max(5)` refuses a rating of 7 (BR-19), `@PositiveOrZero` a negative value (BR-20), and
`@NotNull` a reminder without a time. Nothing below that boundary refused anything:

- **No enum column but `users.role` had a `CHECK`.** V8 gave that one a constraint, and said why: Hibernate's
  `ddl-auto: validate` checks a column's type, never its contents, so a stray `UPDATE` can store a role
  the application cannot read back, and the row then fails on load rather than on write. The same was
  true of every other enum column, `health_upgrades.status` among them, and a status of `DRAFT` would
  fail every list that met the row, as a 500.
- **Nothing bounded the ratings or a numeric value.** A rating of 7, written past the API, would be served
  back as valid and counted by BR-21's midpoint rule.
- **`reminders.reminder_time` and `reminders.enabled` were nullable**, though `ReminderRequest` requires the
  time, `Reminder.create` always writes the flag, and `ReminderDto` declares both non-null.
- **Nothing tied a child row's owner to its parent's.** `progress_entries.user_id` and `reflections.user_id`
  copy the owner of their upgrade, and the owner-scoped reads filter on them (BR-15): since #378 the
  dashboard's streak read filters by `user_id`, while `getStreakSummary` counts by `upgrade_id` alone, and
  the two agree only while the copy is right. `health_upgrades.area_id` must name an area of the same owner
  (BR-18). Each held because the one writer stamps it after an ownership check, and for no other reason.

"Past the API" is not hypothetical: a manual fix in production, a later migration's backfill, a second
writer, or a bug in a path that skips the request record. Each of those meets the schema and nothing else.

## Decision

**A row the domain could not hold is refused by the schema too**: a value it has no name for, and an
owner that is not its parent's. Each rule is a requirement or an enum, restated in SQL, and no bound the
requirements do not state is added.

1. **Every enum column carries a `CHECK (column IN (...))` listing its enum's constants exactly**, named
   `<table>_<column>_check` as V8's `users_role_check` is. A nullable column stays nullable: a `CHECK`
   passes `NULL`. A deprecated constant that is still in the enum, such as `UpgradeType.PROTOCOL`, stays
   in the list, because rows may carry it.
2. **Each numeric bound a requirement states is a `CHECK`**: BR-19's ratings from 1 to 5, on
   `progress_entries.rating`, `reflections.difficulty_rating` and `reflections.benefit_rating`, and
   BR-20's non-negative `progress_entries.numeric_value`. `tracking_configs.target_numeric_value` and
   `health_areas.priority` stay unbounded: no requirement bounds them.
3. **A column the domain always writes is `NOT NULL`**, and its entity says so with
   `@Column(nullable = false)`: `reminders.reminder_time` and `reminders.enabled`.
4. **Each constraint is added `NOT VALID` and then validated**, as ADR-072 lays out, but for the one
   decision 6 names, and a row already
   outside a domain fails the migration, and so the start. What it should become is a decision about
   someone's data, which a migration does not take silently, as V7 does not.
5. **A child row's owner is its parent's, by a composite foreign key.** `health_upgrades` and
   `health_areas` gain `UNIQUE (id, user_id)`, and the single-column keys are replaced:
   `progress_entries` and `reflections` reference `health_upgrades (id, user_id)` through
   `(upgrade_id, user_id)`, `ON DELETE CASCADE` as before, and `health_upgrades` references
   `health_areas (id, user_id)` through `(area_id, user_id)`, `ON DELETE SET NULL (area_id)`, PostgreSQL 15's
   form that clears the area and keeps the owner. Each is named, so code can tell its violation from
   another's.
6. **The area key is left `NOT VALID`.** A row filed before BR-18 may name another user's area, and
   `UpgradeService.update` keeps such a row editable by checking only a changed area. Validating would
   refuse to start on a database holding one. Unvalidated, PostgreSQL still checks every insert and every
   update that changes `area_id` or `user_id`, and skips an update that leaves both as they were on a row
   an earlier transaction wrote, which is the same tolerance the service has. The two child keys are validated: no rule ever let their owners
   differ.
7. **The two copies of each value list are held together by a test.** `EnumColumnCheckContractTest`
   finds every `@Enumerated` field of every entity, reads the `CHECK` its column ends up with once every
   migration has run, and fails the build when the two differ or the `CHECK` is missing.
   `ValueDomainConstraintIT` writes a value outside each domain past the application and expects the
   named constraint to refuse it, and `OwnerConsistencyIT` does the same with a child row naming another
   owner.

## Consequences

**What this makes easy**

- **A bad value is refused where it is written**, by the statement that wrote it, rather than discovered
  later by every reader that meets it.
- **A new enum column cannot be forgotten.** The contract test fails until a migration gives it a `CHECK`.
- **An owner-scoped read and a read by parent agree by construction.** A streak counted by `upgrade_id` and
  one filtered by `user_id` see the same rows, whatever wrote them.

**What this makes hard**

- **Adding an enum constant needs a migration.** The `CHECK` is dropped and added again with the new value,
  in the same change as the constant, or the contract test fails the build. Without the test, the first
  write of the new value would be a 500. The test reads the migrations, not the database, so it catches the
  mismatch in `./mvnw test`, before anything is deployed.
- **Removing or renaming a constant needs a data migration first**, or the `VALIDATE` fails on the rows
  that carry the old name. That was already true, since a row with a name the enum lacks fails on load.
- **A migration that writes a value now meets the constraint.** The seed data in V2 to V7 satisfies every
  one, which the integration tests show by migrating a fresh database from V1.
- **Each owned parent carries a second unique index**, on `(id, user_id)`, maintained on every insert,
  which the foreign keys need and no query uses.
- **The area key is weaker than it looks.** A legacy row with a foreign area stays, and no constraint
  says it is there. The day such rows are gone, a `VALIDATE` makes the key whole.

## Alternatives considered

- **PostgreSQL enum types (`CREATE TYPE ... AS ENUM`).** One declaration per set, and a typed column.
  Rejected: a value cannot be removed from an enum type at all, short of replacing the type, and one added
  by `ALTER TYPE ... ADD VALUE` cannot be used in the transaction that added it, so a migration that adds a
  value and backfills it must be two. Hibernate also binds an `@Enumerated(STRING)` field as `VARCHAR`,
  which PostgreSQL will not take for an enum column, so every such field would need a mapping of its own.
- **A lookup table per enum, with a foreign key.** A value added by an `INSERT`, and a list readable at run
  time. Rejected: nothing reads the list at run time, the Java enum stays the source of truth, and a join
  or a second copy of each name buys nothing over a `CHECK`.
- **Leave it to the application.** Rejected for the reasons in Context: every write that skips the request
  record meets nothing.
- **A trigger that compares the owners.** It could check any rule, and could be written once. Rejected: a
  composite foreign key says the same in a declaration PostgreSQL already enforces, and a trigger is code
  in the schema that no test of the application exercises unless it is written to.
- **Drop the copied `user_id` from the child tables** and read the owner through the upgrade. Rejected for
  now: every owner-scoped read of progress and reflections would need a join, and `findUserIdsWithEntriesOn`,
  the check-in sweep's read, with it.
- **Bound every numeric column a value would plausibly fit**, a priority from 1 to 10, say. Rejected: a
  bound no requirement states is a product rule invented in a migration, and it would refuse input the API
  accepts.

## When to revisit

- **An enum's values start changing often**, so that the migration each change costs is a burden. A lookup
  table then becomes worth its join.
- **A requirement bounds another numeric value**, a target or a priority. Its `CHECK` goes in with the rule.
- **No row with a foreign area is left**, which a query can show. Then the area key is validated.
- **A second writer reaches the database**, such as a reporting job or another service. The constraints
  already cover it; the contract test then also needs to know the other writer's enums.
