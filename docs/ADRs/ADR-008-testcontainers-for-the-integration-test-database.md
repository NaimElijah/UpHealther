# ADR-008: Start the integration-test database from the test source, with Testcontainers

- **Status:** Accepted
- **Date:** 2026-08-25
- **Scope:** `backend/` test sources and the backend CI job. No production code, no runtime dependency —
  every package added here is test-scoped.

## Context

[ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) introduced the split
this project still runs on: `mvn test` needs no database, `mvn verify` boots the application against a
real PostgreSQL. That was the right split. What it left unspecified is *which* PostgreSQL.

Until now the answer was "whichever one is listening". The suite reads `DB_URL`, `DB_USERNAME` and
`DB_PASSWORD`, and `application.yml` defaults them to `localhost:5432/healthupgrades`. That has three
failure modes, and they share a shape — the suite does not fail, it passes against the wrong thing:

- **A local PostgreSQL shadows the intended one.** On the maintainer's machine a native PostgreSQL 16
  service owns 5432, so `docker-compose up -d postgres` starts a container whose port binding loses to
  the service already there, and `mvn verify` connects to the native instance. Flyway then migrates a
  developer's own database, and Hibernate validates against a schema that was never the one under test.
- **Version drift.** `docker-compose.yml` runs `postgres:15-alpine`; a developer machine runs whatever it
  runs. The schema is owned by PostgreSQL-specific migrations and Hibernate boots with
  `ddl-auto: validate`, so the engine version is part of what is being tested.
- **State carried between runs.** A long-lived database accumulates rows. A test that passes only because
  a previous run left a row behind — or fails only because of one — is the kind of flake that costs an
  afternoon.

CI avoided the first two by pinning a service container, at the cost of describing the database in
`.github/workflows/ci.yml` where no developer runs it. The environment CI tested was not the environment
anyone could reproduce locally.

Adding real database tests makes this worse rather than better. This decision is a precondition for the
persistence tests that pin BR-6 (the unique constraint on `progress_entries`) and BR-14 (`@Version`
optimistic locking): both assert what the *database* does, so both are worthless run against an unknown one.

## Decision

Start the database from the test source with **Testcontainers**, and delete the ambient configuration.

Three test-scoped dependencies, all version-managed by the Spring Boot BOM (Testcontainers 1.19.7 under
Boot 3.2.5, so no version tags in `pom.xml`): `spring-boot-testcontainers`,
`org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`.

`support/PostgresIT` holds a `PostgreSQLContainer` running **`postgres:15-alpine` — the same image
`docker-compose.yml` runs** — annotated `@ServiceConnection` so Boot points the `DataSource` at it. Every
`*IT` extends it. The `postgres` service and the `DB_*` environment block come out of the CI workflow.

Two details are deliberate:

- **The container is a singleton, not a `@Container`.** It is started in a static initialiser and left
  unmanaged by the Testcontainers JUnit extension, so one database serves every integration test in a
  build rather than one per test class. Testcontainers' reaper removes it when the JVM exits.
- **Nothing overrides `DB_URL`.** The point is that the ambient value stops being consulted at all during
  `verify`, not that it is replaced with a better one.

## Consequences

**What this makes easy**

- `mvn verify` is the same command everywhere and describes its own database. There is no "start Postgres
  first" step to get wrong, and no way to run it against a database that happens to be listening.
- Tests that assert database behaviour become worth writing. A unique constraint, an optimistic-lock
  collision and a user-scoped query are now pinned against the engine and version that ship.
- Every run starts from an empty schema, migrated by Flyway from `V1`. That also means the migration
  chain is exercised end to end on every build, not just the delta since someone's last run.
- CI and local development stop diverging: the workflow no longer holds configuration that only exists there.

**What this makes hard**

- **`mvn verify` now requires a running Docker daemon.** It is a hard requirement with a clear error, not
  a silent wrong answer, which is the trade this ADR is making — but it is a new prerequisite, and on a
  machine without Docker the integration tests cannot run at all. `mvn test` is unaffected and still needs
  nothing (NFR-11).
- **First run is slow.** Pulling `postgres:15-alpine` costs a one-off download, and every subsequent run
  pays a few seconds of container start.
- **One more moving part in CI.** Docker-in-Docker is available on GitHub's `ubuntu-latest` runners; a
  self-hosted or container-based runner without a Docker socket would need Testcontainers Cloud or a
  return to a service container.

**Neutral**

- No production code changes and nothing ships. `docker-compose.yml` still runs the database for actually
  *running* the application — this decision governs tests only.

## Alternatives considered

- **Keep the `DB_*` environment variables, and document the trap.** Cheapest, and the status quo. Rejected
  because a documented trap is still a trap: the failure mode is a *passing* build against the wrong
  database, which documentation cannot catch and a reviewer cannot see in a diff. **Revisit if** the
  project ever has to build somewhere Docker is genuinely unavailable, at which point the honest move is
  to skip the ITs loudly rather than to point them at an unknown database.

- **An embedded database — H2 or HSQLDB in PostgreSQL compatibility mode.** No Docker, near-instant start.
  Rejected outright: the schema is owned by Flyway migrations written for PostgreSQL, and Hibernate boots
  with `ddl-auto: validate`. Validating against a different engine tests a dialect production never runs,
  and the two invariants that most need a real database — a partial unique constraint and optimistic-lock
  behaviour under concurrent writes — are exactly where compatibility modes diverge. **Revisit:** never,
  while the schema is PostgreSQL-specific.

- **Keep the CI service container and add Testcontainers only locally.** Would leave CI fast. Rejected
  because it keeps the two environments different, which is the defect this ADR exists to remove — and it
  would mean the database configuration lives in two places that can drift apart silently.

- **Testcontainers' reusable-container mode (`withReuse(true)`).** Keeps the container alive between
  builds and saves the start-up cost. Rejected for now: it needs an opt-in file in the developer's home
  directory that CI will not have, so it makes runs differ again — and a container that outlives a build
  reintroduces the carried-state problem in a smaller form. **Revisit when** container start time is a
  measurable share of the build, which at two integration test classes it is not.
