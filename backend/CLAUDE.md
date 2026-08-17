# Backend conventions

Architecture is DDD + Hexagonal. The decision and its rationale are recorded in
`../docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`, and the layering is enforced mechanically by
`HexagonalArchitectureTest` (ArchUnit) — treat that test as the definition of the module boundaries
rather than working from a layout described in prose.

## Conventions that span multiple files (follow these)

- **User scoping is enforced at the query layer, not globally.** Controllers take
  `@AuthenticationPrincipal User user` and pass `user.getId()` down. Repositories scope by user
  (`findByIdAndUserId`, `findByUserIdAndStatus`, …). There is no implicit "current user" — always
  thread `userId` through service calls. A missing/foreign row surfaces as `ResourceNotFoundException`.

- **State transitions live on the entity, not in services.** `HealthUpgrade` owns its state machine
  (`plan`, `activate`, `pause`, `complete`, `abandon`, `reschedule`), each guarding the transition and
  throwing `BusinessRuleException` on an illegal move. Services orchestrate the pattern:
  *load → call the domain method → `repository.save` → publish a domain event → return `toDto`*.
  Do not put status-guard logic in services or controllers.

  `HealthUpgrade` has **no setters**, so this is enforced by the compiler rather than by convention:
  `status` moves only through the transition methods, descriptive fields only through `updateDetails`,
  and difficulty only through `changeDifficulty`. Create one with `HealthUpgrade.create(...)`, which
  validates the invariants a new upgrade must satisfy. The Lombok builder remains for JPA and for tests
  that need an aggregate already in a given state — do not reach for it in production code.

  Lifecycle: `IDEA → PLANNED → ACTIVE ⇄ PAUSED`; `ACTIVE → COMPLETED`; (most states) `→ ABANDONED`;
  `reschedule` on an `ABANDONED` upgrade reactivates it to `PLANNED`. The max-3-concurrent-HARD rule
  is enforced separately in `UpgradeSchedulingService.validateWithinHardLimit`, which the service applies on
  **every** route to a running HARD upgrade — before `entity.activate`, and before promoting an
  already-ACTIVE upgrade to HARD in `update`. An upgrade occupies a HARD slot only while ACTIVE.

- **Domain events are in-process.** Publish via the injected `DomainEventPublisher` (a thin wrapper
  over Spring's `ApplicationEventPublisher`) after a successful state change. The port itself is
  cross-cutting and lives in `common/domain/port/out/`; each **event** belongs to the context that
  raises it (`upgrade/domain/event/`, `tracking/domain/event/`, `reflection/domain/event/`), and only
  the `DomainEvent` marker is shared. Publishing is logged once, generically, in
  `SpringDomainEventPublisher` — do not add per-event log-only listeners.

  A context's published events are part of its published language, so other contexts may consume them;
  that is why `..domain.event..` is a sanctioned cross-context surface in the ArchUnit rules.

- **Exception → HTTP status is centralized** in the global exception handler. Throw the
  right domain exception rather than building `ResponseEntity` status by hand:
  `ResourceNotFoundException` → 404, `BusinessRuleException` → 422, `DuplicateProgressException` /
  optimistic-lock → 409, bean-validation → 400.

- **Optimistic locking** via `@Version` on entities (e.g. `HealthUpgrade.version`) → concurrent edits
  return 409.

- **Cross-module ownership checks**: a service that touches another module's aggregate calls that
  module's service to authorize — e.g. `TrackingService` calls `upgradeService.getUpgrade(userId, id)`
  first to confirm ownership before recording progress.

## Database / migrations

- Schema is **owned by Flyway** and Hibernate runs with `ddl-auto: validate`. Hibernate will **not**
  create or alter tables — any entity change that affects the schema requires a **new
  `V{n}__name.sql` migration**, or the app fails to start on the validate check. Never edit an
  already-applied migration; add a new one.
