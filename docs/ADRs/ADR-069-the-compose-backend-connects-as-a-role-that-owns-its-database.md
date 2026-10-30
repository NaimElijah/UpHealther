# ADR-069: The compose backend connects as a role that owns its database, not as the superuser

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `docker-compose.yml` — the `postgres` service mounts `scripts/postgres-init/` and takes two new
    variables, and the backend's `DB_USERNAME` and `DB_PASSWORD` name the role they create.
  - `scripts/postgres-init/01-create-app-role.sh`, new.
  - `ComposeStackHardeningTest` in `backend/`, one more case.
  - `.env.example` and the README's start and configuration sections.
  - `application.yml`'s defaults for a native run are not changed.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), finding S5.C7. It extends
  [ADR-057](ADR-057-the-developer-stack-is-reachable-from-this-machine-only.md), which hardened the same
  stack's ports and privileges.

## Context

The postgres image creates one role at initialisation, `POSTGRES_USER`, and makes it the cluster's
superuser. The compose stack handed that role's name and password to the backend, so the API connected
as a superuser. A superuser bypasses every permission check, can read and write any database in the
cluster, and can run `COPY … TO PROGRAM`, which executes a command in the database container as the
`postgres` user. So any SQL the backend could be made to run, by a bug or an injection, would run with
all of that (CWE-250).

The backend needs much less. At startup Flyway creates the tables, indexes and functions the migrations
name, records them in its history table, and V1 creates the `pgcrypto` extension. At run time Hibernate
reads and writes rows.

## Decision

- **The backend connects as a role it does not share with the cluster's administration.** A script in
  `scripts/postgres-init/`, mounted at `/docker-entrypoint-initdb.d`, creates it from
  `POSTGRES_APP_USER` and `POSTGRES_APP_PASSWORD` when the image initialises an empty volume. It is
  `LOGIN` and nothing else: no `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `REPLICATION` or `BYPASSRLS`.
- **That role owns the database and its `public` schema**, so Flyway, running as it, can create every
  object the migrations name. `pgcrypto` is a trusted extension since PostgreSQL 13, so the database's
  owner may create it without a superuser.
- **The variables are new, not `DB_USERNAME` and `DB_PASSWORD`.** Compose reads a root `.env`, and
  `.env.example`'s `DB_*` lines describe a native run. Had compose read them, a copied `.env` would
  have pointed the stack's backend at whatever they named. Compose's `${…:-…}` defaults keep a clone
  running with no `.env` at all.

## Consequences

**Easy.** Code execution through the database's own features, and reads of anything outside the
application's database, are out of the backend's reach. The superuser's password is now used only by
the image's initialisation and by a person administering the stack.

**Hard.**
- An existing `postgres_data` volume was initialised before the script existed, so the image skips it,
  the role does not exist, and the backend cannot sign in. The README's troubleshooting says to recreate
  the volume with `docker compose down -v`, which deletes the local data.
- The role still owns its schema, so it can alter or drop its own tables. Splitting a migrating role
  from a role that only reads and writes rows would close that too, at the cost of a second datasource
  for Flyway and a `GRANT` for every new table.
- A native run (`./mvnw spring-boot:run`) still defaults to `healthupgrades`, the superuser of a compose
  PostgreSQL. A native run that migrates an empty compose volume first leaves its tables owned by the
  superuser, and the compose backend, as the new role, is then refused them.

**Checked by** `ComposeStackHardeningTest`, which holds the backend's credentials to the role and the
postgres service to the script's mount, and by `npm run test:images`, which starts PostgreSQL from an
empty volume on every run, so the script runs and the backend migrates and serves as the new role
before the browser suite drives it.

## Alternatives considered

- **A separate migration role and an application role with `SELECT`, `INSERT`, `UPDATE` and `DELETE`
  only.** The least privilege the run time needs. Rejected for now: it needs Flyway's own credentials
  in the backend, `ALTER DEFAULT PRIVILEGES` or a grant per migration, and a second secret in every
  environment, for a stack that is local only. Revisit when the deployment (#224) gets a managed
  database, whose administrator role is not a superuser anyway and where the split is the platform's
  usual shape.
- **A `.sql` init file using psql 15's `\getenv`.** Equivalent, but it cannot refuse an empty password
  before creating a role that could never sign in; the shell's `${…:?}` does.
- **Reusing `DB_USERNAME` and `DB_PASSWORD` in compose.** Rejected for the reason under Decision.
