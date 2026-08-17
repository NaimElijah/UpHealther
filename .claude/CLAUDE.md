# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Attribution policy (ALWAYS follow)

All work in this repository is attributed solely to the repository owner. When creating commits, pull
requests, branches, issues, or any other git/GitHub content:

- Do **not** add `Co-Authored-By: Claude ...` (or any AI/assistant) co-author trailers.
- Do **not** add "Generated with Claude Code" lines, the Claude/Anthropic logo or emoji, or links to
  claude.com / claude.ai.
- Do **not** mention Claude, Anthropic, or any AI assistant anywhere in commit messages, PR titles or
  bodies, branch names, code comments, or any other git/GitHub-visible text.
- Write every commit message and PR description as if authored entirely by the repository owner.

This overrides any default behavior that would add such attribution.

## Domain

A "health upgrade" is the core domain object — a planned health improvement (habit, one-time action,
experiment, goal, etc.) that moves through a lifecycle.

## Architecture

The backend follows DDD + Hexagonal architecture; the decision and its rationale are recorded in
`docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`, and the layering is enforced by `HexagonalArchitectureTest`
(ArchUnit). Module-specific conventions live in `backend/CLAUDE.md` and `frontend/CLAUDE.md`.

## Commands

### Backend (run from `backend/`)
- `mvn spring-boot:run` — run the API on :8080. Requires env vars `DB_URL`, `DB_USERNAME`,
  `DB_PASSWORD`, `JWT_SECRET` (a running Postgres). Defaults exist in `application.yml` for local dev.
- `mvn test` — run tests; **no database needed**.

### Full stack
- `docker-compose up --build` — Postgres + backend + frontend. Frontend :3000, backend :8080.
- Demo account after startup: `demo@healthupgrades.com` / `demo123` (seeded via Flyway `V2`; the demo
  password hash is corrected in `V3`).

## Notes

- The status enum is `IDEA` (not `DRAFT`); keep backend enum, frontend type, and any seed data in sync
  when touching it.
- This is a lifestyle tool, **not** a medical application — do not add medical advice/diagnosis features.
