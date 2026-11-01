# ADR-085: The jar creates no account of its own — a development run creates the demo account through a Flyway callback

- **Status:** Accepted
- **Date:** 2026-11-01
- **Scope:** `V21__delete_the_seeded_demo_account.sql`, `db/demo/afterMigrate__demo_account.sql`,
  `backend/config/application.yml`, `docker-compose.yml`, `support/PostgresIT`.
- **Issue:** [#84](https://github.com/NaimElijah/UpHealther/issues/84), with the evidence added by security
  scan 05 ([#339](https://github.com/NaimElijah/UpHealther/issues/339), S5.A2) and database scan 06
  ([#340](https://github.com/NaimElijah/UpHealther/issues/340), D6.1).
- **Related:** [ADR-041](ADR-041-the-refresh-cookie-is-always-secure.md) and
  [ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md) each turned down an environment signal and said
  to revisit if #84 introduced one. *Decision* explains why this is not one.

## Context

`V2__seed_data.sql` inserts a demo account, with six areas, six upgrades and four tracking
configurations. `V3` and `V6` then set its password to one this repository publishes in the README and in
`V6`'s comment. `application.yml` names no Flyway location, so the jar and the image run all three in every
database they migrate, production included.

That is an account anyone can sign in to, on every deployment. Two things make it worse than an account
with a weak password:

- **Its id is fixed.** `a0000000-0000-0000-0000-000000000001` is the one account id an operator can know on
  a fresh install. Setting `ADMIN_BOOTSTRAP_USER_ID` to it makes the published sign-in an administrator, by
  a one-line mistake (#339, S5.A2).
- **Production is about to exist.** The AWS deployment
  ([#198](https://github.com/NaimElijah/UpHealther/issues/198)) runs the image against a database that V2
  would seed at its first start (#340, D6.1).

The forces pull in different directions.

- **A clone must still run with something to look at.** The README tells an evaluator to sign in as the
  demo account after `docker compose up --build`, and the repository baseline asks for a one-command run.
- **V2 is applied everywhere and is never edited.** Flyway checksums a migration, comments included, so
  the account can be removed only by a later migration.
- **The two earlier ADRs closed the usual route.** Both turned down a gate on an environment signal (a
  profile, a variable every run path must carry and that fails closed when missing), for the cost of
  carrying it through compose, `spring-boot:run`, the slices, the ITs and CI.
- **Whatever creates the account is code in the jar.** An image that contains the script can run it; the
  question is what makes it run.

## Decision

**The jar creates no account. A development run creates the demo account, through a Flyway callback that
only it names.**

- **`V21__delete_the_seeded_demo_account.sql` deletes the account** by its fixed id, and nothing else. It
  does not match on the address: an account holding `demo@healthupgrades.com` under another id was
  registered by somebody and is theirs. Every foreign key to `users` is `ON DELETE CASCADE`
  ([ADR-063](ADR-063-one-schema-with-cross-context-foreign-keys.md)), so its areas, upgrades, tracking
  configurations, progress, reflections, reminders, notifications and sessions go with it.
- **`db/demo/afterMigrate__demo_account.sql` creates it again.** It is a Flyway SQL callback, not a
  migration: Flyway runs it after every migrate and never records it in `flyway_schema_history`. It creates
  the account, its six areas, six upgrades and four tracking configurations, all or nothing, and only when
  the account is missing. It writes nothing when the account exists, or when another account holds the
  address, so whatever an owner changed or deleted stays that way across restarts.
  - It is written as the domain would write each row, where V2 did not: an `ACTIVE` upgrade has an
    `actual_start_date`, the areas carry V5's emoji, and the password is V6's.
- **The switch is a Flyway setting that names one script location:**
  `spring.flyway.callback-locations: classpath:db/demo`.
  - `backend/config/application.yml` sets it. Spring Boot reads that file only for a run from `backend/`,
    and neither the jar nor the image contains it ([ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md)).
  - `docker-compose.yml` sets it for the backend as the literal
    `SPRING_FLYWAY_CALLBACK_LOCATIONS: "classpath:db/demo"`. It is not a variable: the file is the local
    stack, and the account is part of what the local stack is for.
  - The jar's own `application.yml` names no callback location. `ShippedFlywayConfigurationTest` pins that,
    and pins `spring.flyway.locations` to `classpath:db/migration`.
- **`PostgresIT` empties the callback location** for the shared integration-test database, because Maven
  runs the tests from `backend/` and so reads the file that names it. Every `*IT` therefore migrates as a
  deployment does, and `ApplicationContextIT` shows the result holds no demo account.
- **The script lives in `db/demo`, beside `db/migration` and never under it.** With no callback location
  named, Flyway looks for callbacks among the migration locations, recursively. A script nested under
  `db/migration` would run everywhere.

**This is not the environment signal ADR-041 and ADR-042 turned down.** Both rejected a security check that
reads a statement about where the application runs, and asked to revisit "if #84 introduces such a signal".
This does not, for three reasons.

1. **It says nothing about the environment.** The setting names a directory of scripts, as
   `locations` names the migrations. It does not claim "this is development", and no code asks it that.
2. **Its absence is the production behaviour.** The signals those ADRs rejected had to be present on every
   run path and had to fail closed when missing. This one fails safe when missing: with no setting there is
   no account. A misspelt or dropped line costs a developer a sign-in, never a deployment its safety.
3. **Nothing refuses on it.** No check reads it, so it cannot be spoofed to pass one. It cannot gate a
   security decision, and none is made to depend on it.

What keeps a deployment clean is what it does not do — its configuration never names the location — and
that is the same structure ADR-042 relies on for the signing key: present where a developer works, absent
where the image runs.

**Applied migrations stay as they are.** V2, V3 and V6 are untouched and still run, in that order, on a new
database. V21 then deletes what they wrote. The account is changed from now on in the callback, never by a
migration.

## Consequences

- **A deployment cannot hold the demo account by omission.** `DemoAccountMigrationIT` migrates an empty
  database with the jar's settings and finds neither the account nor its areas. `ApplicationContextIT`
  does the same on the assembled application, and `ShippedFlywayConfigurationTest` holds the jar's file to
  no callback location.
- **Every existing local database loses the demo account's data once.** V21 deletes the account with
  everything it owns: progress, reflections, anything a developer entered. The next start of a stack that
  names the callback creates the account again, empty of what a user entered. A developer who wants to keep
  something creates their own account first. `DemoAccountMigrationIT` covers a database migrated up to V20
  and then to the latest.
- **A schema change must update the callback in the same commit.** The script is written against today's
  schema, and a migration that adds a `NOT NULL` column or a constraint the rows break fails the next
  development start. `DemoAccountMigrationIT` runs the callback against the migrated schema's constraints,
  so a mismatch fails the build there. Nothing else reminds the author, and `backend/CLAUDE.md` says so.
- **A misspelt or missing switch silently gives no account.** That is the fail-safe direction, and it is
  quiet: the README's sign-in stops working. `DemoAccountSeedContractTest` reads `backend/config/` and the
  compose file and fails when either stops naming `classpath:db/demo` as a callback location, and when a
  file in `db/demo` is not a callback script.
- **A file in `db/demo` that is a migration never runs.** Flyway reads a callback location for callbacks
  alone, so a `V` or `R` file put there is silently ignored. `DemoAccountSeedContractTest` rejects one.
- **Setting the variable in production would recreate a published credential.** An operator who sets
  `SPRING_FLYWAY_CALLBACK_LOCATIONS=classpath:db/demo` on the image gets the account on its next start,
  because the script is in the jar. This is the gap ADR-042 accepted for `JWT_SECRET` in the compose file,
  accepted here on the same terms: the task definition for #198 names no callback location, and a
  deployment is built from the image, not from `docker-compose.yml`.
- **The image still carries the credential.** V2's address, and V6's hash and the comment naming the
  password, ship in every jar, and so does the callback. Nothing in the jar can keep the published
  password out of the image; this decision keeps the account out of the databases.
- **The compose stack and a run from `backend/` behave as before.** The account signs in with the README's
  credential after `docker compose up --build`.
- **Flyway writes no history for the callback,** so a database the callback ran on validates against a
  configuration without it. Dropping the setting needs no repair.

## Alternatives considered

- **An `R__` repeatable migration in a location only development names.** Rejected. Flyway 12.4.0's
  `MigrationInfoImpl.validate` fails an applied repeatable migration that a later run cannot resolve
  ("Detected applied migration not resolved locally"), and the default `ignoreMigrationPatterns` is
  `*:future` only. A database that compose migrated would then refuse to start without the location, which
  is the first start of any run that omits it. A callback writes no history, so it has no such state.
- **`spring.flyway.locations` instead of `callback-locations`.** Rejected. The setting replaces the
  migration location, so it must restate `classpath:db/migration` and cannot be forgotten without
  disabling every migration. When callback locations are set, `FlywayExecutor` scans callbacks only there,
  so a `V` or `R` file in `db/demo` is never applied, and the setting need not name `db/migration`.
- **A `@Profile` or a Java startup runner that inserts the account.** Rejected. Both need a statement
  about the environment that every run path carries (compose, `spring-boot:run`, the slices, the ITs, CI)
  and that fails closed when missing: exactly what ADR-041 and ADR-042 turned down.
- **A V21 gated on a Flyway placeholder** (`${demoAccount}`) **that development sets.** Rejected. V21
  runs once per database and is then recorded as applied. A database that migrated with the placeholder set
  wrongly, or unset where a default says "keep", skips the delete for good and keeps the account even after
  the setting is fixed; only another migration could remove it. The safe behaviour would depend on a value
  being right at one moment, where the decision above depends on a value being absent.
- **Disable the account instead of deleting it.** Rejected, though #339 suggested it. It leaves a
  published password, a fixed id and a claimed address in every deployment's database, one administrator's
  "enable" from being a sign-in again, and an address that no one who registers can then use.
- **A `filesystem:` location that compose mounts.** Rejected. It keeps the script out of the image, but
  not the credential: V2's address and V6's hash and comment ship in every jar regardless. It would also
  make the compose stack depend on a bind mount for what it needs to run.
- **`spring-boot-maven-plugin` arguments** to set the location for `spring-boot:run`. Rejected for
  ADR-042's reason: it would cover only that goal, not an IDE launch, and would override a location the
  developer set.

## When to revisit

- **`docker-compose.yml` is used to deploy anything,** even a staging copy. It names the location as a
  literal, so the account would be created there.
- **The task definition for #198 is written.** It must name no callback location, and a review of it should
  check that. If the deployment is configured by a mechanism that sets Flyway properties wholesale, check
  that it does not carry this one.
- **A production SQL callback is needed** for any reason. Callback locations are then not free to mean
  "development only", and the two uses need separating.
- **Flyway changes its callback semantics:** where it searches for callbacks when none are named, whether it
  records them, or how `callback-locations` combines with `locations`. `ShippedFlywayConfigurationTest` and
  `DemoAccountMigrationIT` are the canaries, and the Flyway upgrade is the moment to read the release notes.
