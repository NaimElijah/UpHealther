# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Domain

A "health upgrade" is the core domain object — a planned health improvement (habit, one-time action,
experiment, goal, etc.) that moves through a lifecycle.

## Architecture

The backend follows DDD + Hexagonal architecture; the decision and its rationale are recorded in
`docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`, corrected and extended by
`docs/ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md`. The layering is
enforced by `HexagonalArchitectureTest` (ArchUnit). Module-specific conventions live in
`backend/CLAUDE.md` and `frontend/CLAUDE.md`.

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
