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
- `e2e/` — the Playwright smoke suite: one browser run through the composed stack, which
  `npm run test:images` starts and CI's images job runs (ADR-052). A package of its own, formatted by
  the frontend's Prettier and neither linted nor type-checked.
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
- `npm run setup` — `setup:frontend` (`npm ci` in `frontend/`), then `setup:e2e` (`npm ci` in `e2e/`
  and the Chromium Playwright pins, about 315 MiB). Once per clone, and after either lockfile changes.
  CI's frontend job runs only the first, and its images job only the second.
- `npm start` — `docker compose up --build`.
- `npm run changelog -- --tag vX.Y.Z` — prepends release X.Y.Z's section to `CHANGELOG.md`, through
  git-cliff's image (needs Docker, and the tags fetched). One step of the README's Releasing section;
  `npm test` does not run it (ADR-051).
- `npm run generate:wire-types` — rewrites `frontend/src/types/api.generated.ts` from the request and
  response records the controllers declare, by running `FrontendWireTypesContractTest` with
  `-Dwire-types.write=true` (ADR-053). Run it after changing a wire record, and commit the file with
  the change: `./mvnw test` fails until they agree. Needs a JDK, not Docker.
- `npm test` — the whole CI gate, the same scripts CI's jobs run (ADR-044): `test:diagrams`,
  `test:frontend`, `test:backend`, then `test:images`. Each also runs alone. Needs Docker, for the
  backend's ITs and the image check.
- `npm run test:images` — builds both images and starts the stack from them until every service is
  healthy, as a throwaway compose project, then drives it with the Playwright suite in `e2e/` and
  takes it down (ADR-045, ADR-052). It publishes only the frontend, on `127.0.0.1:3000`, keeping
  port 3000 because `http://localhost:3000` is the one origin CORS allows, and refuses to start while
  something answers there. A dev server bound only to `::1`, as Vite is where `localhost` resolves to
  IPv6 first, does not collide. It refuses to start while the
  `healthupgrades-*` containers of your own stack exist, because their names are fixed, so
  `docker compose down` first. The stack's subnet is fixed too, and is not
  checked first: a network already on it fails the check after the build. Interrupted, it leaves its
  containers holding those names, and the next run clears them first; a plain `docker compose down`
  does not, because they belong to project `uphealther-images-check`. A failed suite leaves its
  trace in `e2e/test-results/` (`npx playwright show-trace <zip>` in `e2e/`); CI uploads the same as
  the artifact `e2e-test-results`.
- `cd e2e && npm test` — the browser suite alone, against whatever answers on `127.0.0.1:3000` (your
  own `npm start` stack, say). The browser resolves `localhost` to IPv4, so a dev server bound only to
  `::1` is out of its reach. Each run registers an account there and spends two of the ten sign-in
  attempts a minute; `docker compose restart backend` resets the limiter.
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
  `npm run build`, in that order. `format` and `format:check` also cover the root `package.json`,
  `scripts/`, and `e2e/`'s `package.json`, `playwright.config.ts` and `tests/`.

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
  when touching it. The frontend type is generated from the enum, and `FrontendWireTypesContractTest`
  fails the build until `npm run generate:wire-types` has been run; nothing checks the seed data.
- The frontend's request and response types are generated from the backend's records, never written by
  hand (NFR-10, ADR-053). A wire record's component that can be null carries JSpecify's `@Nullable`;
  a request component is either `@Nullable` or carries a not-null constraint; no Jackson annotation
  shapes a wire record. The generator fails the build on any of these, or on a type it cannot map.
- Branches are `type/short-kebab-description`, cut from `dev` and merged back by PR; `dev` → `main` is
  the release PR. Commits are Conventional Commits, one logical change each.
