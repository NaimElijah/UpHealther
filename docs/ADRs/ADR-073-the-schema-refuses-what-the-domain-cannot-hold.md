# ADR-073: The schema refuses what the domain cannot hold

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `backend/src/main/resources/db/migration/V12__value_domains.sql`.
  - `Reminder`, whose two columns become `@Column(nullable = false)`.
  - `EnumColumnCheckContractTest` and `ValueDomainConstraintIT`, which enforce it.
  - BR-19 and BR-20 in `docs/requirements/requirements.md`.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D6.10. Generalises the policy
  `V8__user_role_and_enabled.sql` stated for one column, and follows
  [ADR-072](ADR-072-every-migration-bounds-its-lock-wait.md) for how its constraints are added.

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

"Past the API" is not hypothetical: a manual fix in production, a later migration's backfill, a second
writer, or a bug in a path that skips the request record. Each of those meets the schema and nothing else.

## Decision

**A value the domain has no name for is refused by the schema too.** Each rule is the requirement or
the enum, restated in SQL, and no bound the requirements do not state is added.

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
4. **Each constraint is added `NOT VALID` and then validated**, as ADR-072 lays out, and a row already
   outside a domain fails the migration, and so the start. What it should become is a decision about
   someone's data, which a migration does not take silently, as V7 does not.
5. **The two copies of each value list are held together by a test.** `EnumColumnCheckContractTest`
   finds every `@Enumerated` field of every entity, reads the `CHECK` its column ends up with once every
   migration has run, and fails the build when the two differ or the `CHECK` is missing.
   `ValueDomainConstraintIT` writes a value outside each domain past the application and expects the
   named constraint to refuse it.

## Consequences

**What this makes easy**

- **A bad value is refused where it is written**, by the statement that wrote it, rather than discovered
  later by every reader that meets it.
- **A new enum column cannot be forgotten.** The contract test fails until a migration gives it a `CHECK`.

**What this makes hard**

- **Adding an enum constant needs a migration.** The `CHECK` is dropped and added again with the new value,
  in the same change as the constant, or the contract test fails the build. Without the test, the first
  write of the new value would be a 500. The test reads the migrations, not the database, so it catches the
  mismatch in `./mvnw test`, before anything is deployed.
- **Removing or renaming a constant needs a data migration first**, or the `VALIDATE` fails on the rows
  that carry the old name. That was already true, since a row with a name the enum lacks fails on load.
- **A migration that writes a value now meets the constraint.** The seed data in V2 to V7 satisfies every
  one, which the integration tests show by migrating a fresh database from V1.

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
- **Bound every numeric column a value would plausibly fit**, a priority from 1 to 10, say. Rejected: a
  bound no requirement states is a product rule invented in a migration, and it would refuse input the API
  accepts.

## When to revisit

- **An enum's values start changing often**, so that the migration each change costs is a burden. A lookup
  table then becomes worth its join.
- **A requirement bounds another numeric value**, a target or a priority. Its `CHECK` goes in with the rule.
- **A second writer reaches the database**, such as a reporting job or another service. The constraints
  already cover it; the contract test then also needs to know the other writer's enums.
