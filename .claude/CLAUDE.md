# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Domain

A "health upgrade" is the core domain object — a planned health improvement (habit, one-time action,
experiment, goal, etc.) that moves through a lifecycle.

## Requirements

`docs/requirements/requirements.md` states what the system must do: capabilities, business rules and
invariants, non-functional requirements, and the non-goals. Each entry names the test that enforces it
— or, for the few no test can observe, the command or the manual check — so it can be checked rather
than trusted. Read it before proposing work — a change that satisfies no requirement, or contradicts
one, is a conversation before it is code — and update it in the same commit when what the system must
do changes.

## Architecture

`docs/architecture/architecture.md` describes the system as it is: components, communication, data
flow, external dependencies, and the structural decisions not visible from the file layout. Read it
before changing anything that crosses a boundary, and update it in the same commit when behaviour
changes.

The backend follows DDD + Hexagonal architecture; the decision and its rationale are recorded in
`docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`, corrected and extended by
`docs/ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md`. The layering is
enforced by `HexagonalArchitectureTest` (ArchUnit). Module-specific conventions live in
`backend/CLAUDE.md` and `frontend/CLAUDE.md`.

## Commands

### Backend (run from `backend/`)
- `./mvnw spring-boot:run` — run the API on :8080. Needs a PostgreSQL on `localhost:5432` with
  database, user and password all `healthupgrades` (`docker compose up -d postgres`). `DB_URL`,
  `DB_USERNAME`, `DB_PASSWORD` and `JWT_SECRET` override the defaults in `application.yml`.
- `./mvnw test` — run tests; **no database needed**.
- Always the wrapper, never a bare `mvn`: it runs the pinned Maven, and the enforcer refuses an older
  one, and any JDK but 21, at `validate` (ADR-030, NFR-51). From PowerShell, `.\mvnw.cmd`.

### Full stack
- `docker compose up --build` — Postgres + backend + frontend. Frontend :3000, backend :8080,
  Postgres :5432.
- Demo account after startup: `demo@healthupgrades.com` / `demo1234` (seeded via Flyway `V2`; the
  hash is corrected in `V3` and raised to the eight-character minimum of BR-17 in `V6`). Never edit an
  applied migration to change it — Flyway checksums the comments too; add another `UPDATE`.

## Notes

- The status enum is `IDEA` (not `DRAFT`); keep backend enum, frontend type, and any seed data in sync
  when touching it. `FrontendEnumContractTest` fails the build when the enum and the type drift; nothing
  checks the seed data.
- This is a lifestyle tool, **not** a medical application — do not add medical advice/diagnosis features.
