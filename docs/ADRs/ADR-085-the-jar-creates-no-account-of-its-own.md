# ADR-085: The jar creates no account of its own — a development run creates the demo account from a seed the image never carries

- **Status:** Accepted
- **Date:** 2026-11-01
- **Scope:** `V21__delete_the_seeded_demo_account.sql`, `backend/config/db/demo/afterMigrate__demo_account.sql`,
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
  #84 itself says a clone with an empty database and no way in is a worse first run.
- **V2 is applied everywhere and is never edited.** Flyway checksums a migration, comments included, so
  the account can be removed only by a later migration.
- **The two earlier ADRs closed the usual route.** Both turned down a gate on an environment signal (a
  profile, a variable every run path must carry and that fails closed when missing), for the cost of
  carrying it through compose, `spring-boot:run`, the slices, the ITs and CI.
- **Whatever creates the account is code or data that can travel.** A script in the image can be run by a
  setting someone copies from the compose file. The less the image carries that can create the account, the
  less a setting can do.

## Decision

**The jar creates no account. A development run creates the demo account, from a seed script the jar and
the image do not contain.**

- **`V21__delete_the_seeded_demo_account.sql` deletes the account** by its fixed id, and nothing else. It
  does not match on the address: an account holding `demo@healthupgrades.com` under another id was
  registered by somebody and is theirs. Every foreign key to `users` is `ON DELETE CASCADE`
  ([ADR-063](ADR-063-one-schema-with-cross-context-foreign-keys.md)), so its areas, upgrades, tracking
  configurations, progress, reflections, reminders, notifications and sessions go with it. V2's address and
  V6's hash and comment still ship in every jar, but they are inert: V21 deletes what they write in the same
  migrate, before the server listens.
- **`backend/config/db/demo/afterMigrate__demo_account.sql` creates it again.** It is a Flyway SQL callback,
  not a migration: Flyway runs it after every migrate and never records it in `flyway_schema_history`. It
  creates the account, its six areas, six upgrades and four tracking configurations, all or nothing, and
  only when the account is missing. It writes nothing when the account exists, or when another account holds
  the address, so whatever an owner changed or deleted stays that way across restarts.
  - It is written as the domain would write each row, where V2 did not: an `ACTIVE` upgrade has an
    `actual_start_date`, the areas carry V5's emoji, and the password is V6's.
  - It sits under `backend/config/`, with the development signing key
    ([ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md)). The Dockerfile builds from `src/` and copies
    only the jar, so neither the jar nor the image contains it.
- **The switch is a Flyway setting that names a `filesystem:` location.**
  - `backend/config/application.yml` sets `spring.flyway.callback-locations: filesystem:config/db/demo`. The
    path is relative to the working directory, which for `./mvnw spring-boot:run` and for Maven's tests is
    `backend/`. Boot reads that file only for a run from `backend/`.
  - `docker-compose.yml` bind-mounts `./backend/config/db/demo` read-only at `/demo-seed` in the backend
    service, and sets `SPRING_FLYWAY_CALLBACK_LOCATIONS: "filesystem:/demo-seed"` as a literal, not a
    variable: the file is the local stack, and the account is part of what the local stack is for.
  - The jar's own `application.yml` names no callback location. `ShippedFlywayConfigurationTest` pins
    that, and pins `spring.flyway.locations` to `classpath:db/migration`, in every document of the file
    whatever profile guards it (compose activates `json-logs`), and that the jar holds no other
    configuration file Boot would load beside it.
- **A copied setting alone does nothing.** Flyway 12.4.0's `FileSystemScanner.scanForResources` skips a
  location that does not exist with an ERROR in the log, unless `failOnMissingLocations` is set, and Boot's
  default for it is false. A deployment that copied the compose variable names `/demo-seed` in an image that
  has no such folder, so it creates no account. A Fargate task cannot bind-mount a repository directory
  either. The seed has to be put there as well as named.
- **Contract tests keep the image free of the seed.** `DemoAccountSeedContractTest` fails when:
  - the checkout's file or the compose file stops naming its location, or compose stops mounting the folder
    read-only;
  - the seed folder holds anything but Flyway callback scripts;
  - a `.sql` file sits under `src/main/resources` outside `db/migration`;
  - the Dockerfile's runtime stage copies anything but the jar;
  - a production source names the seed's folder, either spelling of the setting, or the account's id.

  `FlywayMigrationsTest` pins that the contract tests refuse any script beneath `db/migration`, so a
  callback nested there, where Flyway would find it with no callback location named, fails the build.
- **`PostgresIT` empties the callback location** for the shared integration-test database, because Maven
  runs the tests from `backend/` and so reads the file that names it. Every `*IT` therefore migrates as a
  deployment does. `DemoAccountMigrationIT` migrates databases of its own, naming the folder by absolute
  path with `failOnMissingLocations(true)`, so a wrong path fails there instead of passing over nothing.

**This is not the environment signal ADR-041 and ADR-042 turned down.** Both rejected a security check that
reads a statement about where the application runs, and asked to revisit "if #84 introduces such a signal".
This does not, for three reasons.

1. **It says nothing about the environment.** The setting names a folder of scripts, as `locations` names the
   migrations. It does not claim "this is development", and no code asks it that.
2. **Its absence is the production behaviour.** The signals those ADRs rejected had to be present on every
   run path and had to fail closed when missing. This one fails safe when missing: with no setting, or with
   a setting and no folder, there is no account. A dropped line costs a developer a sign-in, never a
   deployment its safety.
3. **Nothing reads it.** No production code consults the setting or the account's existence, so it cannot
   gate a security decision. `DemoAccountSeedContractTest` fails on a production source that names the seed,
   the setting or the account's id, which is what holds that reason in place.

**The fixed id stays.** V21 needs it to identify V2's account without touching an account somebody
registered under the address, and the seed's rows name each other by fixed ids. The seed creates the account
only where the checkout's file is present, so the id reaches a database only when someone ships that file
and names it. An operator who set `ADMIN_BOOTSTRAP_USER_ID` to that id before this change clears it: after
V21 no account holds the id, and a development run that recreates it would otherwise promote the published
sign-in.

**Applied migrations stay as they are.** V2, V3 and V6 are untouched and still run, in that order, on a new
database. V21 then deletes what they wrote. The account is changed from now on in the callback, never by a
migration.

## Consequences

- **A deployment cannot hold the demo account by omission, and not by a copied setting.** It would need the
  seed file in the container and a setting that points Flyway at it. `DemoAccountMigrationIT` migrates an
  empty database with the jar's settings and finds no account; `ShippedFlywayConfigurationTest` holds the
  jar's file to no callback location; and the contract tests above keep the seed out of the image.
  `ApplicationContextIT` shows that V21 deletes V2's account on Boot's own Flyway path.
- **The rule a deployment must keep, stated by value:** no setting may point Flyway's callback or migration
  locations at the seed. That covers `SPRING_FLYWAY_CALLBACK_LOCATIONS`, the other spellings Boot binds
  (`SPRING_FLYWAY_CALLBACKLOCATIONS`, `SPRING_APPLICATION_JSON`, a `-Dspring.flyway…` argument), and
  `SPRING_FLYWAY_LOCATIONS`, because with no callback location named Flyway looks for callbacks among
  `locations`. The task definition that writes them,
  [#207](https://github.com/NaimElijah/UpHealther/issues/207) under epic #198, names none of these. Even
  then nothing creates the account without the file in the container.
- **Deploying through `docker-compose.yml` still creates the account.** The literal cannot be overridden
  from `.env`, and the folder is part of the checkout. This gap is accepted on ADR-042's terms: compose is
  the local stack, and `.env.example` says so.
- **Every existing local database loses the demo account's data once.** V21 deletes the account with
  everything it owns: progress, reflections, anything a developer entered. The next start of a stack that
  names the seed creates the account again as seeded, without what was entered. A developer who wants to keep
  something creates their own account first. `DemoAccountMigrationIT` covers a database migrated up to V20
  and then to the latest.
- **A schema change must update the callback in the same commit.** The script is written against today's
  schema, and a migration that adds a `NOT NULL` column or a constraint the rows break fails the next
  development start. `DemoAccountMigrationIT` runs the callback against the migrated schema's constraints,
  so a mismatch fails the build there. Nothing else reminds the author, and `backend/CLAUDE.md` and the
  README's Migrations paragraph say so.
- **A misspelt or missing switch silently gives no account.** That is the fail-safe direction, and it is
  quiet: the README's sign-in stops working. `DemoAccountSeedContractTest` fails when the checkout's file or
  the compose file stops naming its location.
- **A run from the wrong directory has no account.** The checkout's location is relative to `backend/`. An
  IDE launched at the repository root logs an ERROR for the missing folder and creates nothing (it also
  refuses to start without a signing key, per ADR-042).
- **A file in the seed folder that is a migration never runs.** Flyway reads a callback location for
  callbacks alone, so a `V` or `R` file put there is silently ignored. `DemoAccountSeedContractTest` rejects
  one.
- **The compose stack and a run from `backend/` still sign in with the README's credential,** with one
  difference: the demo upgrades now carry start dates, as the domain writes an `ACTIVE` upgrade, where V2's
  had none.
- **Flyway writes no history for the callback,** so a database the callback ran on validates against a
  configuration without it. Dropping the setting needs no repair.

## Alternatives considered

- **A classpath location in the jar** (`classpath:db/demo`), which was the first form of this decision.
  Rejected. The image then carries the one thing that can recreate the account, and one copied setting
  recreates it: `docker-compose.yml`'s variable, or `SPRING_FLYWAY_LOCATIONS=classpath:db`. Moving the script
  out of the image leaves nothing to activate.
- **An `R__` repeatable migration in a location only development names.** Rejected. Flyway 12.4.0's
  `MigrationInfoImpl.validate` fails an applied repeatable migration that a later run cannot resolve
  ("Detected applied migration not resolved locally"), and the default `ignoreMigrationPatterns` is
  `*:future` only. A database that compose migrated would then refuse to start without the location. A
  callback writes no history, so it has no such state.
- **`spring.flyway.locations` instead of `callback-locations`.** Rejected. The setting replaces the
  migration location, so it must restate `classpath:db/migration` and cannot be forgotten without
  disabling every migration. When callback locations are set, `FlywayExecutor` scans callbacks only there,
  so a `V` or `R` file in the seed folder is never applied, and the setting need not name `db/migration`.
- **A `@Profile("demo")` bean or a Java startup runner that inserts the account.** Rejected, though it would
  fail safe too: a runner behind a default-off property or profile adds behaviour only when present, as the
  callback does. The reasons are others.
  - It puts production code and a bean in the jar, and with them the account's creation, in every image.
  - It needs the ports of four contexts (`user`, `healtharea`, `upgrade`, `tracking`) to create the rows
    through the domain, or raw JDBC in production code.
  - A callback runs inside Flyway's migrate, before `AdminBootstrapRunner` and in the migration's
    transaction; a runner needs its own ordering and its own transaction to match.
  - Compose already sets `SPRING_PROFILES_ACTIVE` (`json-logs`), so a copied profile list carries whatever
    is added to it, a `demo` profile included.
- **A V21 gated on a Flyway placeholder** (`${demoAccount}`) **that development sets.** Rejected. V21
  runs once per database and is then recorded as applied. A database that migrated with the placeholder set
  wrongly, or unset where a default says "keep", skips the delete for good and keeps the account even after
  the setting is fixed; only another migration could remove it. The safe behaviour would depend on a value
  being right at one moment, where the decision above depends on a value being absent.
- **Disable the account instead of deleting it.** Rejected, though #339 suggested it. It leaves a
  published password, a fixed id and a claimed address in every deployment's database, one administrator's
  "enable" from being a sign-in again, and an address that no one who registers can then use.
- **Delete the account and put nothing in its place** (#84's first remark, and the simplest end state).
  Rejected for now. A clone would start with an empty database and no way in, which #84 calls a worse first
  run, and the README's evaluator flow signs in at once. Its cost is the seed's upkeep, an update for every
  schema change the rows meet.
  - **Revisit:** if the seed needs changing for schema changes more than once a quarter, or if the README's
    first run becomes registering an account.
- **Randomise the password at startup and print it once** (#84's option 2). Rejected. It needs a Java
  runner, with the reasons above, and it writes a credential to the log, which the repository's rules forbid
  everywhere. It also replaces the credential the README and the browser suite can quote with one found in a
  log.
- **Keep the seed and force a password change at first sign-in** (#84's option 3). Rejected. The account
  would still exist, with a published password, in every deployment until someone signed in, and
  [#57](https://github.com/NaimElijah/UpHealther/issues/57), password change, is not built.
- **`spring-boot-maven-plugin` arguments** to set the location for `spring-boot:run`. Rejected for
  ADR-042's reason: it would cover only that goal, not an IDE launch, and would override a location the
  developer set.

## When to revisit

- **`docker-compose.yml` is used to deploy anything,** even a staging copy. It mounts the seed and names it,
  so the account would be created there.
- **The task definition for #207 is written.** It must name none of the settings in the rule above, and a
  review of it should check that, including any mechanism that sets Flyway properties wholesale.
- **Anything is to ship the seed with the image, or mount it in a deployment.** The protection above then
  rests on the setting alone.
- **A production SQL callback is needed** for any reason. Callback locations are then not free to mean
  "development only", and the two uses need separating.
- **A change reads the seed's setting, or whether the demo account exists, to decide any behaviour.** That is
  the environment signal ADR-041 and ADR-042 rejected arriving through this record. The contract test that
  keeps production sources from naming the seed is the first place it would show.
- **Flyway changes how it treats a missing location or a callback location:** `failOnMissingLocations`
  defaulting to true is harmless, but anything that made a missing folder create or fail differently, or
  changed where callbacks are searched for, needs the protection above re-examined.
  `ShippedFlywayConfigurationTest` and `DemoAccountMigrationIT` are the canaries, and a Flyway upgrade is the
  moment to read the release notes.
- **The seed's upkeep outgrows its use,** per the first alternative's trigger above.
