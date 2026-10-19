# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
It loads at the start of every session; `backend/CLAUDE.md` and `frontend/CLAUDE.md` load only once a
file in that module is read or edited, so what a session needs before it opens a file belongs here.

## Domain

A "health upgrade" is the core domain object — a planned health improvement (habit, one-time action,
experiment, goal, etc.) that moves through a lifecycle. This is a lifestyle tool, **not** a medical
application — do not add medical advice/diagnosis features (`requirements.md` §5.1).

## Where things are

- `backend/` — the API: Spring Boot 4.1 on Java 21, DDD + Hexagonal, one package per bounded context
  under `com.healthupgrades`. The decision is `docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`,
  corrected and extended by
  `docs/ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md`; the layering
  is enforced by `HexagonalArchitectureTest` (ArchUnit).
- `frontend/` — the SPA: React 18, Vite 8, TanStack Query, Tailwind.
- `docs/requirements/requirements.md` — what the system must do.
- `docs/architecture/architecture.md` — the system as it is; `docs/architecture/arch-diagrams/` holds
  its diagrams, three of them generated from the code.
- `docs/ADRs/` — why each decision was made, one `ADR-NNN-short-kebab-title.md` per decision.
- `docs/api.md` — the HTTP surface.

## Keeping the documents in step

- **Requirements.** `requirements.md` states capabilities, business rules and invariants,
  non-functional requirements, and the non-goals. Each entry names the test that enforces it — or, for
  the few no test can observe, the command or the manual check — so it can be checked rather than
  trusted. Read it before proposing work — a change that satisfies no requirement, or contradicts one,
  is a conversation before it is code — and update it in the same commit when what the system must do
  changes. When an *Enforced by* cell changes, recount the numbers in its §1 header with the rule
  commit `2cb32c5` states: nothing fails when they drift, and they have.
- **Architecture.** `architecture.md` describes components, communication, data flow, external
  dependencies, and the structural decisions not visible from the file layout. Read it before changing
  anything that crosses a boundary, and update it in the same commit when behaviour changes.
- **Diagrams.** The context map, the class diagram and the ER diagram are generated from the imports,
  the package layout and the Flyway migrations. A change to any of those means running
  `python docs/architecture/arch-diagrams/generate.py` and committing its output with the change. CI's
  `diagrams` job runs it with `--check` and fails the PR otherwise — a docs-only PR included.

## Commands

### Repository root
- `npm run setup` — installs the frontend's dependencies (`npm ci` in `frontend/`). Once per clone,
  and after the lockfile changes.
- `npm start` — `docker compose up --build`.
- `npm test` — the whole CI gate, the same scripts CI's jobs run (ADR-044): `test:diagrams`,
  `test:frontend`, `test:backend`, then `test:images`. Each also runs alone. Needs Docker, for the
  backend's ITs and the image check.
- `npm run test:images` — builds both images and starts the stack from them until every service is
  healthy, as a throwaway compose project with no published ports, then takes it down (ADR-045).
  It refuses to start while the `healthupgrades-*` containers of your own stack exist, because their
  names are fixed, so `docker compose down` first. The stack's subnet is fixed too, and is not
  checked first: a network already on it fails the check after the build. Interrupted, it leaves its
  containers holding those names, and the next run clears them first; a plain `docker compose down`
  does not, because they belong to project `uphealther-images-check`.
- These run identically from PowerShell, Git Bash and a Linux shell, because npm runs them through
  `cmd.exe` or `sh` and their bodies stay `cd dir && tool args`. A command no one string spells in
  both shells goes into `scripts/*.mjs`, as `mvnw.mjs` and `python.mjs` do. `mvnw.cmd` calls
  `powershell`, so a session whose `PATH` lacks `C:\Windows\System32\WindowsPowerShell\v1.0` fails
  in the wrapper.

### Backend (run from `backend/`)
- `./mvnw spring-boot:run` — the API on :8080. Needs a PostgreSQL on `localhost:5432` with database,
  user and password all `healthupgrades` (`docker compose up -d postgres`). `DB_URL`, `DB_USERNAME`
  and `DB_PASSWORD` override the defaults in `application.yml`. The signing key comes from
  `backend/config/application.yml`, which only a run from `backend/` reads, and `JWT_SECRET` overrides
  it; the jar has none and refuses to start without one (ADR-042).
- `./mvnw test` — unit, web-slice and architecture tests; **no database, no Docker**.
- `./mvnw clean verify` — the above, the `*IT` integration tests and the format check. Needs a running
  **Docker daemon**: the ITs start their own PostgreSQL through Testcontainers. What CI runs is the
  root's `npm run test:backend`: this, then `npm run audit:backend`, OSV-Scanner over the whole
  dependency tree, failing on any known vulnerability (ADR-047). A finding is fixed by a bump, or
  accepted in `backend/osv-scanner.toml` with a reason and an `ignoreUntil`, which
  `OsvScannerExceptionsAreDatedTest` requires because the scanner would take a bare `id`.
- `./mvnw spotless:apply` — format.
- One test: `./mvnw test -Dtest=UpgradeServiceTest` (or `Class#method`). One IT:
  `./mvnw verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ApplicationContextIT`.
- Always the wrapper, never a bare `mvn`: it runs the pinned Maven, and the enforcer refuses an older
  one, and any JDK but 21, at `validate` (ADR-030, NFR-51). From PowerShell, `.\mvnw.cmd`, with each
  `-D` argument quoted.

### Frontend (run from `frontend/`)
- `npm run dev` — the SPA on :3000, proxying `/api` and `/ws` to :8080.
- `npm test` — Vitest, one run. One file: `npx vitest run src/api/client.test.ts`.
- `npx tsc` — typecheck. Vitest does not typecheck, so otherwise a type error surfaces only at
  `npm run build`.
- `npm run format` — format.
- What CI runs is the root's `npm run test:frontend`: `npm run format:check`, `npm run lint`,
  `npm run check:colours`, `npm run test:coverage`, `npm audit --omit=dev --audit-level=high`,
  `npm run build`, in that order. `format` and `format:check` also cover the root `package.json` and
  `scripts/`.

### Full stack
- `docker compose up --build` — Postgres + backend + frontend. Frontend :3000, backend :8080,
  Postgres :5432.
- Demo account after startup: `demo@healthupgrades.com` / `demo1234` (seeded via Flyway `V2`; the
  hash is corrected in `V3` and raised to the eight-character minimum of BR-17 in `V6`). Never edit an
  applied migration to change it — Flyway checksums the comments too; add another `UPDATE`.

A change is ready for a PR when the root `npm test` passes: it runs what CI's four jobs run, the
backend's `clean verify` and audit, the frontend list above, the diagram check and the image check.
The jobs are required checks, so a PR into `dev` or `main` cannot merge until they pass (ADR-043).
Renaming, adding or removing a job means editing ruleset 25310599 just before merging that PR, then
re-triggering the other open PRs with a push or a close and reopen.

## Conventions across both modules

- Tests are named `Given<state>_When<action>_Then<outcome>`
  (`docs/ADRs/ADR-009-test-levels-boundaries-and-naming.md`).
- A formatter version bump is a `style(backend)` or `style(frontend)` commit of its own, and its SHA
  goes into `.git-blame-ignore-revs`.
- The status enum is `IDEA` (not `DRAFT`); keep backend enum, frontend type, and any seed data in sync
  when touching it. `FrontendEnumContractTest` fails the build when the enum and the type drift; nothing
  checks the seed data.
- Branches are `type/short-kebab-description`, cut from `dev` and merged back by PR; `dev` → `main` is
  the release PR. Commits are Conventional Commits, one logical change each.
