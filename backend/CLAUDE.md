# Backend conventions

The conventions to follow when changing the backend; `../.claude/CLAUDE.md` points at the
requirements, the architecture and the ADRs, and has the commands. Treat `HexagonalArchitectureTest`
(ArchUnit) as the definition of the module boundaries rather than any layout described in prose.

The contexts under `com.healthupgrades` are `admin`, `auth`, `dashboard`, `healtharea`, `notification`,
`reflection`, `reminder`, `tracking`, `upgrade` and `user`; `common` holds the shared kernel and the
cross-cutting adapters. A context typically keeps entities and outbound ports in `domain/` (`model`,
`port/out`), use cases in `application/` with inbound ports in `application/port/in`, and adapters in
`adapter/{in,out}/*` — but **not** identically, whatever ADR-001's canonical package layout (key
choice 3) says: `auth` orchestrates over `user` for identity while owning its own `AuthSession`;
`admin` orchestrates over `user` and `auth` and owns no aggregate; and `dashboard` is a
read/composition model with no domain or outbound side. All three are deliberate, not drift.

## Conventions that span multiple files (follow these)

- **User scoping is enforced at the query layer, not globally.** Controllers take
  `@AuthenticationPrincipal SecurityUser principal` and pass `principal.getId()` down. `SecurityUser`
  is the security adapter's principal, not the `user` domain entity. Repositories scope by user
  (`findByIdAndUserId`, `findByUserIdAndStatus`, …). There is no implicit "current user" — always
  thread `userId` through service calls. A missing/foreign row surfaces as `ResourceNotFoundException`.
  The scheduled sweeps are the one reader of every user's rows, and they read through a port of their
  own — `UpgradeSweepQuery`, `ProgressSweepQuery`, `ReminderSweepQuery` — never through the
  user-facing query port; `HexagonalArchitectureTest` lets only the jobs' application services depend
  on a `*SweepQuery`. Each is implemented by a package-private class of its own (`UpgradeSweeps`,
  `ProgressSweeps`, `ReminderSweeps`), never by the service a controller holds, or the controller could
  call the sweep through it (ADR-064). The two other unscoped reads follow the same pattern:
  `TrackingConfigQuery` (`TrackingConfigReads`) and `AccountListQuery` (`AccountListing`, every account,
  which only `admin` may hold). `HexagonalArchitectureTest` keeps every such implementer package-private
  (ADR-065). A new read a request can reach takes the owner's id instead.

- **A role opens paths, never rows.** `USER` and `ADMIN` live on the `users` row and are read on every
  request, never carried in the token, so a revoked role takes effect on the next call. `ADMIN` unlocks
  `/api/admin/**` and changes no query: the scoping above applies to an administrator exactly as it does
  to anyone else. Do not put a role check in a service — if one looks necessary, the boundary is wrong
  (ADR-016).

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
  is enforced separately in `UpgradeSchedulingService.validateWithinHardLimit`, which the service
  applies on **every** route to a running HARD upgrade — before `entity.activate`, and before promoting
  an already-ACTIVE upgrade to HARD in `update`. An upgrade occupies a HARD slot only while ACTIVE.

- **Domain events are in-process.** Publish via the injected `DomainEventPublisher` (a thin wrapper
  over Spring's `ApplicationEventPublisher`) after a successful state change. The port itself is
  cross-cutting and lives in `common/domain/port/out/`; each **event** belongs to the context that
  raises it (`upgrade/domain/event/`, `tracking/domain/event/`, `reflection/domain/event/`,
  `auth/domain/event/`), and only the `DomainEvent` marker is shared. Publishing is logged once,
  generically, in `SpringDomainEventPublisher` — do not add per-event log-only listeners. A context's
  published events are part of its published language, so other contexts may consume them; that is
  why `..domain.event..` is a sanctioned cross-context surface in the ArchUnit rules.

  A `@TransactionalEventListener(phase = AFTER_COMMIT)` that writes must also be
  `@Transactional(propagation = REQUIRES_NEW)`. Without it, the write joins the transaction that has
  just committed, and it is silently never stored (#169, ADR-028). A new notification listener also
  goes into `NotificationCommitIT`'s parameters, and that IT fails until it does.

- **Exception → HTTP status is centralized** in the global exception handler. Throw the right domain
  exception rather than building `ResponseEntity` status by hand: `ResourceNotFoundException` → 404,
  `BusinessRuleException` → 422, `ConflictException` / optimistic-lock → 409, bean-validation
  → 400 with a field → message map. A context names its own conflict by subclassing the kernel's
  `ConflictException`, as `tracking`'s `DuplicateProgressException` does, so the kernel never names a
  context's type. Refusals decided inside the security chain reach the same handler:
  `ErrorBodySecurityHandlers` forwards an anonymous request as `AuthenticationRequiredException` (401
  with a bearer challenge) and an authorization failure as `AccessDeniedException` (403), so do not
  write a response body from a filter.

  **Framework exceptions are not yours to map.** `GlobalExceptionHandler` extends Spring's
  `ResponseEntityExceptionHandler`, so an unbindable body, a path variable that will not convert, an
  unsupported method or media type already carry the status Spring defines; `handleExceptionInternal`
  only re-clothes the result in this API's `ErrorResponse` body. To change one, **override its
  `handleXxx` hook** — adding a second `@ExceptionHandler` for a type the parent already maps is an
  ambiguous mapping and fails at startup. See
  `../docs/ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md`.

- **A filter added to the security chain is not also a servlet filter.** Boot registers every `Filter`
  bean with the container, so a `@Component` filter that `SecurityConfig` adds with `addFilterBefore`
  runs twice, and which copy counts depends on order. Mark it `@FilterRegistration(enabled = false)`,
  as `JwtAuthenticationFilter` is; `JwtFilterRegistrationIT` checks the container holds no copy (#238).

- **Correlation is the runtime's job, not the call site's.** Every log line carries a trace id because
  the work runs inside an observation — `ServerHttpObservationFilter` for HTTP, a `SchedulingConfigurer`
  for `@Scheduled` jobs, `StompTracingChannelInterceptor` for STOMP frames. **Never write the MDC by
  hand**, and never pass an id through a method signature. A new inbound adapter is the one thing that
  needs thought: say how it gets a span, or its log lines will be the only anonymous ones. Read the id
  back only where it leaves the process, through `CorrelationId.of(tracer)`, which handles the three
  shapes of "no id". See `../docs/ADRs/ADR-007-request-correlation-through-micrometer-tracing.md`.

- **A log level is a claim about who has to act**, as ADR-010 defines each one. `com.healthupgrades`
  runs at `INFO`, so a `DEBUG` line is invisible in any deployment until someone sets `LOG_LEVEL_APP` —
  write one only where that is the right answer.

  **Nothing personal goes in a log line**: ids and enum values, never a title, an email, a reflection
  body, a progress note or an IP address. That is NFR-6, and it applies to the message *and* to
  anything handed to a `{}` placeholder. The one exception is the single ERROR line that reports an
  unexpected fault with its stack trace, message included: a 5xx, from `GlobalExceptionHandler`, or a
  scheduled run that threw, which Spring's scheduler logs (ADR-026). Hibernate's SQL exception logger,
  `org.hibernate.orm.jdbc.error`, is `OFF` in `application.yml` for this reason: it quotes the refused
  row on every SQL failure, handled or not. Do not turn it back on (ADR-027, ADR-032). Spring's
  `ExceptionHandlerExceptionResolver` is pinned at `INFO` beside it, because its DEBUG line quotes every
  rejected value of a refused body, a password included (ADR-065). A line that
  repeats per item names an exception by its type, never its message. The output format is chosen by
  the `json-logs` profile in `src/main/resources/logback-spring.xml`; do not write a `<pattern>` there,
  because Boot's imported `defaults.xml` is what puts the trace id on a plain-text line. See
  `../docs/ADRs/ADR-010-structured-logging-and-a-level-policy.md`.

- **A state change is recorded through `AuditTrail`, not through a `log.info`.** Every state-changing
  use case wraps its body in `auditTrail.recording(action, userId, resourceId, ...)`, or in
  `recordingCreation(...)` when the resource id exists only once the operation has run. Either records
  the attempt whether it succeeded or was refused — a refusal is the entry somebody actually goes
  looking for, and BR-15 means an attempt on another user's record leaves no other trace. So a child
  record's creation (a reflection, a reminder, progress, a tracking configuration) is recorded against
  its upgrade, through `recording`, and its action's `AuditedResource` is `HEALTH_UPGRADE`: a refusal
  then names the upgrade that was probed (ADR-065).
  Authentication outcomes, which have no body to wrap, call `auditTrail.record(AuditEvent)` directly.
  Adding an operation means adding a constant to `AuditAction` (in `common`, deliberately: one
  vocabulary, readable in one place) and wrapping the body; it does **not** mean a new log statement.

  `AuditEvent` holds two enums and two identifiers and nothing else, so there is nowhere to put a title
  or an email even by accident. Keep it that way — `AuditEventTest` fails the build on a field that
  could hold free text. The same bound applies to metric tags: an action and an outcome are closed sets,
  a user id is not. See `../docs/ADRs/ADR-011-audit-as-a-log-stream.md`.

- **Optimistic locking** via `@Version`, on `HealthUpgrade` only (`AuthSession` deliberately has none;
  `AuthSessionRepositoryPort` says why) → two saves that overlap inside the server return 409. No
  request carries the version back yet, so an edit from a stale page is not refused (#194).

- **The application layer never imports an adapter.** A service takes a command record from
  `application/port/in` and returns a domain object (or a result record where there is no aggregate,
  e.g. `StreakSummary`). A `*WebMapper` in `adapter/in/web` translates in both directions; `auth` and
  `admin`, with the smallest web surfaces, translate in the controller instead. Do not give a service
  an HTTP request record or a DTO — the ArchUnit rule will fail the build, which is the point: a
  wire-format change must not reach a use-case signature. The kernel's adapters count too, though
  most of their packages are not called `adapter`: an `application` or `domain` class may reach
  `common` only in `common.domain`, so `common.security`, `common.websocket`, `common.observability`,
  `common.ratelimit`, `common.adapter` and any kernel package added later are out. Declare a port
  instead, as `auth` does with `AccessTokenIssuerPort`, which `JwtAccessTokenIssuer` implements over
  `JwtTokenProvider` (ADR-064).

- **A request or response record is also the frontend's type.** `FrontendWireTypesContractTest`
  renders every record a controller accepts or returns into `frontend/src/types/api.generated.ts`
  and fails until `npm run generate:wire-types` has been run and its output committed (ADR-053).
  Mark a component that can be null on the wire with JSpecify's `@Nullable`, judged from its mapper,
  its entity and its column. An unmarked one is typed non-null, and nothing at runtime checks that
  claim. A request component is either `@Nullable` or carries `@NotNull`, `@NotBlank` or
  `@NotEmpty`. Shape the JSON with the record, not with Jackson annotations: the generator models
  none, and fails on one. `FrontendWireTypesJsonTest` writes and reads every wire record through
  Boot's mapper and fails when the JSON differs from the rendered type, so an enum's `toString()`, an
  extra getter or a mapper setting fails the build too.

- **Cross-context reads go through the other context's inbound port**, never its service class — e.g.
  `TrackingService` calls `upgradeQuery.getOwnedUpgrade(userId, id)` to confirm ownership first.

- **When a context needs something another context has, and the arrow would point the wrong way,
  invert it.** `upgrade` declares `UpgradeTrackingSummaryPort` for the tracking config its response
  carries, and `tracking` implements it, so the graph stays acyclic (ADR-002). The shared kernel does
  the same: `common` depends on no context, so it declares `SessionStatusPort` and
  `AccountCredentialsPort` in `common.domain.port.out`, and `auth` and `user` supply them (ADR-062).

## Lint

The compiler is the linter. Every `-Xlint` category is on except `processing` and `serial`, and `try`
in test sources, and a warning fails `./mvnw compile`, so `./mvnw test` fails on one too (ADR-039
records each exclusion's reason). Fix a warning rather than suppress it. Where a suppression is
genuinely right, `@SuppressWarnings` goes on the narrowest element, with a comment saying why. No
bug-pattern checker runs: Error Prone and SpotBugs found nothing here that was not already tracked
(ADR-040 has the numbers and the revisit triggers).

## Formatting

Layout belongs to palantir-java-format, run through Spotless (ADR-037). `./mvnw spotless:apply`
rewrites every Java source under `src/main` and `src/test`, and `./mvnw verify` fails on any file it
would change; do not format by hand. Imports are one block, static ones first, so there is no
hand-kept grouping to preserve. Javadoc is not reflowed.

## Tests

Four levels, and the rule for choosing between them is in
`../docs/ADRs/ADR-009-test-levels-boundaries-and-naming.md`: write a test at the **cheapest level that
can actually observe the behaviour**, and never at one that cannot. Mock our own ports; never mock
PostgreSQL — if the assertion is about what the database does, it is an `*IT` or it is not a test of
that.

- Shared fixtures live in `src/test/java/com/healthupgrades/support/`: `AUser`, `AnUpgrade`,
  `ATrackingConfig`, `AProgressEntry` build entities with the required fields filled in, and
  `WebSliceSupport` wires a `@WebMvcTest` to the **real** `SecurityConfig` and `JwtAuthenticationFilter`
  — authenticate with `WebSliceSupport.authenticateAs(authenticator, userId)` and `bearer(...)` rather
  than disabling security, which would assert the opposite of FR-5. A slice mocks
  `BearerTokenAuthenticator`, not `JwtTokenProvider`: it is the seam both transports authenticate
  through, so mocking it leaves the filter and the security config real. A service test audits
  through `support/RecordingAuditTrail`, never a Mockito mock of `AuditTrail`: `recording` is a
  `default` method, which a mock stubs to return `null` without running the operation.
- **Strict stubs and carry-on loops.** `PotentialStubbingProblem` is a `RuntimeException`, so a loop
  that catches one per item and carries on (`NotificationSweepService`'s sweeps) counts a stub mismatch
  as a real failure, and the test flakes with `HashMap` order. Stub every argument the loop will see,
  as `nudgeFailsFor` in `NotificationSchedulerTest` does, and re-run before trusting a green.
- `./mvnw test` needs **no database**; keep it that way. The `*IT`s that `./mvnw verify` adds boot the
  application against a real PostgreSQL they **start themselves**: every `*IT` extends
  `support/PostgresIT`, which runs the image `support/PostgresImage` names (the exact tag compose runs;
  `ContainerImagesArePinnedTest` fails the build otherwise) through Testcontainers and wires the
  `DataSource` to it with `@ServiceConnection`. So `verify` needs a running **Docker daemon**, not a
  database you started, and never consults the `DB_*` env vars. Do not point a test at an ambient
  database: a local PostgreSQL on 5432 is accepted silently and the suite then passes against the wrong
  schema (ADR-008). `ApplicationContextIT` is what catches a missing `@Bean` in the hand-wired
  `*BeansConfig` classes and a missing Flyway migration, neither of which any unit test can see.
- **The jar's `application.yml` has no signing key** (ADR-042). A test context gets one from
  `src/test/resources/config/application.yml`, or from the development key in `config/application.yml`,
  which outranks it when Maven runs the tests from `backend/`. A test of what the *shipped*
  configuration does must name `classpath:/application.yml` alone and remove the process environment, as
  `ShippedJwtConfigurationTest` does. Setting `JWT_SECRET` to an empty string is not the same as leaving
  it unset: an empty variable counts as present and skips a fallback. An IDE launch of the application
  itself (not a test) needs `backend/` as its working directory, or `JWT_SECRET` exported, or it refuses
  to start.
- **Every `*IT` shares one database.** `PostgresIT` starts a single container for the whole run;
  `@DataJpaTest` ITs roll back, unless one opts out as `AuthSessionRowLockIT` does to hold a lock across
  two transactions (it deletes what it commits), but `@SpringBootTest` ITs commit rows that every later
  class sees, and failsafe's order is the filesystem's. Assert only on rows the test itself created. A
  test of what one migration does to rows written before it cannot use that database, which Flyway has
  already taken to the latest version: it takes one of its own from `PostgresIT.createIsolatedDatabase`
  and migrates it with Flyway's `target`, as `AuthSessionMigrationIT` does. When an IT fails
  on a tree that passed elsewhere, reproduce with `-Dfailsafe.runOrder=alphabetical` and
  `reversealphabetical` before re-running anything.
- **After changing a signature, a constructor's arity or a record's shape, build with `clean`.** An
  incremental `test-compile` reuses stale classes from `target/` and reports success over test sources
  that no longer compile. Likewise, a source restored by copy or move keeps its older timestamp, so
  Maven keeps running the class compiled from the edit: `touch` it, or `clean`.

## Database / migrations

Schema is **owned by Flyway** and Hibernate runs with `ddl-auto: validate`: it will **not** create or
alter tables, so any entity change that affects the schema needs a **new `V{n}__name.sql` migration**,
or the app fails to start. Never edit an already-applied migration; add a new one.
