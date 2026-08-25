# Backend conventions

`../docs/requirements/requirements.md` states what the system must do — the capabilities, the business
rules, and the class or test that enforces each. `../docs/architecture/architecture.md` describes the
system as it is — contexts, communication, data flow and the structural decisions behind the layout.
This file covers the conventions to follow when changing it.

Architecture is DDD + Hexagonal. The decision and its rationale are recorded in
`../docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`, corrected and extended by
`../docs/ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md`. The layering is
enforced mechanically by `HexagonalArchitectureTest` (ArchUnit) — treat that test as the definition of
the module boundaries rather than working from a layout described in prose.

The layout is **not** identical across contexts, whatever ADR-001 §3 says: `auth` orchestrates over
`user` and owns no aggregate, and `dashboard` is a read/composition model with no domain or outbound
side. Both are deliberate, not drift.

## Conventions that span multiple files (follow these)

- **User scoping is enforced at the query layer, not globally.** Controllers take
  `@AuthenticationPrincipal User user` and pass `user.getId()` down. Repositories scope by user
  (`findByIdAndUserId`, `findByUserIdAndStatus`, …). There is no implicit "current user" — always
  thread `userId` through service calls. A missing/foreign row surfaces as `ResourceNotFoundException`.

- **State transitions live on the entity, not in services.** `HealthUpgrade` owns its state machine
  (`plan`, `activate`, `pause`, `complete`, `abandon`, `reschedule`), each guarding the transition and
  throwing `BusinessRuleException` on an illegal move. Services orchestrate the pattern:
  *load → call the domain method → `repository.save` → publish a domain event → return the aggregate*.
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
  optimistic-lock → 409, bean-validation → 400 with a field → message map.

  **Framework exceptions are not yours to map.** `GlobalExceptionHandler` extends Spring's
  `ResponseEntityExceptionHandler`, so an unbindable body, a path variable that will not convert, an
  unsupported method or media type already carry the status Spring defines; `handleExceptionInternal`
  only re-clothes the result in this API's `ErrorResponse` body. To change one, **override its
  `handleXxx` hook** — adding a second `@ExceptionHandler` for a type the parent already maps is an
  ambiguous mapping and fails at startup. See
  `../docs/ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md`; the catch-all
  that used to swallow all of these as 500s is what issue #22 was.

- **Correlation is the runtime's job, not the call site's.** Every log line carries a trace id because
  the work runs inside an observation — `ServerHttpObservationFilter` for HTTP, a `SchedulingConfigurer`
  for `@Scheduled` jobs, `StompTracingChannelInterceptor` for STOMP frames. **Never write the MDC by
  hand**, and never pass an id through a method signature. A new inbound adapter is the one thing that
  needs thought: say how it gets a span, or its log lines will be the only anonymous ones. Read the id
  back only where it leaves the process, through `CorrelationId.of(tracer)`, which handles the three
  shapes of "no id". See `../docs/ADRs/ADR-007-request-correlation-through-micrometer-tracing.md`.

- **Optimistic locking** via `@Version` on entities (e.g. `HealthUpgrade.version`) → concurrent edits
  return 409.

- **The application layer never imports an adapter.** A service takes a command record from
  `application/port/in` and returns a domain object (or a result record where there is no aggregate,
  e.g. `StreakSummary`). The `*WebMapper` in `adapter/in/web` translates in both directions. Do not
  give a service an HTTP request record or a DTO — the ArchUnit rule will fail the build, which is the
  point: a wire-format change must not reach a use-case signature.

- **Cross-context reads go through the other context's inbound port**, never its service class — e.g.
  `TrackingService` calls `upgradeQuery.getOwnedUpgrade(userId, id)` to confirm ownership before
  recording progress.

- **When a context needs something another context has, and the arrow would point the wrong way,
  invert it.** `upgrade` declares `UpgradeTrackingSummaryPort` describing what it wants, and `tracking`
  implements it. That is what keeps the graph acyclic while the upgrade response still carries tracking
  config; see ADR-002.

## Tests

Four levels, and the rule for choosing between them is in
`../docs/ADRs/ADR-009-test-levels-boundaries-and-naming.md`: write a test at the **cheapest level that
can actually observe the behaviour**, and never at one that cannot. Mock our own ports; never mock
PostgreSQL — if the assertion is about what the database does, it is an `*IT` or it is not a test of
that. Name tests `Given<state>_When<action>_Then<outcome>`.

- Shared fixtures live in `src/test/java/com/healthupgrades/support/`: `AUser`, `AnUpgrade`,
  `ATrackingConfig`, `AProgressEntry` build entities with the required fields filled in, and
  `WebSliceSupport` wires a `@WebMvcTest` to the **real** `SecurityConfig` and `JwtAuthenticationFilter`
  — authenticate with `WebSliceSupport.authenticateAs(...)` and `bearer(...)` rather than disabling
  security, which would assert the opposite of FR-5.
- `mvn test` — unit, web-slice and architecture tests. **No database needed**; keep it that way.
- `mvn verify` — the above plus the `*IT` integration tests, which boot the application against a real
  PostgreSQL. They **start it themselves**: every `*IT` extends `support/PostgresIT`, which runs a
  `postgres:15-alpine` container through Testcontainers and wires the `DataSource` to it with
  `@ServiceConnection`. So `verify` needs a running **Docker daemon**, not a database you started — and
  the `DB_*` env vars are not consulted during a test run at all. Do not point a test at an ambient
  database: a local PostgreSQL listening on 5432 is accepted silently and the suite then passes against
  the wrong schema, which is the failure
  `../docs/ADRs/ADR-008-testcontainers-for-the-integration-test-database.md` was written about.
  `ApplicationContextIT` is what catches a missing `@Bean` in the hand-wired `*BeansConfig` classes and a
  missing Flyway migration, neither of which any unit test can see.

## Database / migrations

- Schema is **owned by Flyway** and Hibernate runs with `ddl-auto: validate`. Hibernate will **not**
  create or alter tables — any entity change that affects the schema requires a **new
  `V{n}__name.sql` migration**, or the app fails to start on the validate check. Never edit an
  already-applied migration; add a new one.
