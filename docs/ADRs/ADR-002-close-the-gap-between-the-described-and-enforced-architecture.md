# ADR-002: Close the gap between the described and the enforced architecture

- **Status:** Accepted
- **Date:** 2026-08-17
- **Supersedes in part:** ADR-001 (the pragmatic DTO exception, the claim of a uniform layout, and the
  enforced-rules list)
- **Scope:** `backend/`. The React frontend is untouched and the HTTP contract is unchanged.

## Context

ADR-001 adopted DDD + Hexagonal and stated that violations fail the build. `backend/CLAUDE.md` went
further and told contributors to "treat that test as the definition of the module boundaries rather
than working from a layout described in prose."

A conformance audit found the structure sound in its bones — a genuinely framework-free domain,
hand-wired pure domain services, correct port/adapter direction on the driven side, package-private
Spring Data repositories, `@Transactional` confined to application services — but the enforcement
weaker than the documents claimed. Five ArchUnit rules covered roughly half of ADR-001's commitments,
and the bounded-context rule's ignore list matched **every** cross-context edge that existed, so it
could not fail. Contributors were being told to trust a guard that was partly decorative.

Four concrete problems sat behind that gap:

1. **A cycle.** `upgrade` and `tracking` depended on each other. Every edge was individually
   sanctioned — inbound ports, domain models, published DTOs — so no rule objected, while the pair
   could not be reasoned about or extracted independently.
2. **The application layer depended on the web adapter.** Six of eight contexts imported their own
   `adapter/in/web` package, taking HTTP request records as use-case input and returning response DTOs
   as output. ADR-001 sanctioned only the response half; request records were never covered.
3. **Events lived in the shared kernel.** All ten domain events sat in `common/domain/event`, named for
   aggregates in other contexts. Because the slice rule ignores `common` wholesale, this satisfied the
   guard by relocation rather than by decoupling, and made the kernel a surface three contexts wrote to.
4. **The layout is not uniform.** ADR-001 claims one canonical package layout "identical for every
   bounded context". It never was: `auth` and `dashboard` have no domain layer or outbound side.

The audit also surfaced five behavioural defects, fixed separately and not the subject of this record.

## Decision

### Fix the boundaries

- **Break the cycle by inverting the dependency.** `tracking → upgrade` (ownership checks) is correct
  and stays. `upgrade` no longer reaches back; it declares `UpgradeTrackingSummaryPort` — what it needs,
  in a record it owns — and `tracking` supplies it. Both arrows now run the same way.
- **Turn the application layer away from the web.** Every service states its own input and output
  shapes as command and result records under `application/port/in`, and returns domain objects. A
  `*WebMapper` per context translates in both directions at the boundary. This makes the pattern
  `upgrade` and `dashboard` already used the rule rather than the exception.
- **Move each domain event to the context that raises it.** Only the `DomainEvent` marker stays shared;
  `DomainEventPublisher` moves to `common/domain/port/out`, where the outbound-ports rule finally
  covers it.
- **Let aggregates own their invariants.** `HealthUpgrade` and `Reminder` lose their setters;
  `HealthUpgrade` gains a validating `create` factory, and `Reminder` gains `ReminderDays` plus
  `isDueAt`, taking behaviour back from an adapter in another context that was re-parsing its storage
  format.

### Correct what ADR-001 asserts

- `auth` and `dashboard` are **deliberately** two-layer contexts. `auth` orchestrates over `user` and
  owns no aggregate; `dashboard` is a read/composition model with nothing to persist. Neither is drift.
- `application/port/in` holds **cross-context read views and use-case input/output records**, not
  driving ports. Only one of nine controllers is bound to a port interface, because there is one
  driving adapter. This is named for what it is rather than pretended otherwise.
- The pragmatic DTO exception of ADR-001 is **withdrawn**. It is no longer needed and no longer true.

### Enforce it

The ArchUnit suite grows from five rules to ten. Added: bounded contexts free of cycles; application
must not depend on **any** adapter; inbound adapters must not depend on outbound adapters; inbound
query and command ports must be interfaces; controllers only in the web adapter; Spring Data
repositories package-private. The domain purity rule widens beyond Spring to bean validation, servlet,
Jackson and Hibernate.

The sanctioned cross-context surfaces become: `common`, another context's `domain.model`, its
`domain.event`, its `application.port` (either direction — a port is published contract whichever way
it faces), and its published `*Dto`/`*WebMapper`. This is narrower than before in the way that matters:
a consumer must now name the context it consumes from instead of everything arriving through `common`.

Each rule was added only after the code satisfied it, and each was verified to fail on the violation it
targets before being kept.

### Build a floor under it

`ApplicationContextIT` boots the application against a real PostgreSQL, run by Failsafe during `verify`
so `mvn test` stays database-free. It catches the two failures that previously shipped green: a lost
`@Bean` in the hand-wired `*BeansConfig` classes, and an entity change without a Flyway migration.
`UpgradeSchedulingService` and `GlobalExceptionHandler` gain the tests they never had. JaCoCo reports
coverage without gating it, and `npm audit --audit-level=high` fails the frontend build on known
vulnerabilities.

**The backend has no vulnerability audit yet.** OWASP dependency-check was added, run, and removed
again: it cannot populate its database without an `NVD_API_KEY`, and fails outright without one. A
step that always fails, or one marked `continue-on-error`, is the same decorative guard this record
exists to remove. Enabling it means setting the repository secret first.

## Consequences

**Positive**

- The graph is acyclic and every context is independently reasonable about.
- The dependency arrow runs inward everywhere: no application class imports an adapter.
- What the documents promise is now what the build checks, so `backend/CLAUDE.md` can honestly point at
  the suite as the definition of the boundaries.
- A missing migration or a broken bean graph fails CI instead of deploy.

**Trade-offs**

- More types: a command or result record and a mapper per context. Deliberate — it is what makes the
  dependency direction enforceable rather than aspirational.
- `UpgradeTrackingConfigDto` duplicates the field list of `TrackingConfigDto`. That duplication is the
  price of the two contexts owning their own contracts; a rename on one side no longer breaks the other.
- Enum values cross the `upgrade`/`tracking` boundary as their names, losing compile-time typing at that
  one seam. The JSON is unchanged because that is what Jackson already emitted.
- `mvn verify` now needs a database. `mvn test` deliberately still does not.

**Neutral**

- No behaviour or wire-format change, beyond the five bug fixes recorded in their own commits.
- The STOMP push adapter depends on the web adapter's mapper, on purpose: the two transports must emit
  an identical payload and previously did so via two copies that could drift.

## Alternatives considered

- **A composition adapter for the upgrade/tracking cycle.** Rejected: keeping the JSON identical that
  way requires moving `UpgradeController` and most of `/api/upgrades` out of the upgrade context, and
  routing composition through a neutral module simply reintroduces the cycle one hop longer.
- **Dropping `trackingConfig` from the upgrade response.** Cleanest backend, rejected as a breaking wire
  change that would force the daily check-in page into an N+1 fetch.
- **Persistence-ignorant domain** (POJOs + separate JPA entities + mappers). Rejected again, as in
  ADR-001: a large refactor across nine contexts for little gain at this size. **Revisit if** the domain
  must be reused outside this service, or ORM semantics start dictating domain design.
- **Value objects** for identifiers, quantities and ratings. Rejected for now; the codebase has none and
  introducing them is a wide change. **Revisit when** a transposed-identifier or unit-mismatch bug
  reaches production — the unit-comparison defect fixed during this work is one warning shot.
- **Inbound command ports for controllers.** Rejected: there is one driving adapter, so the interfaces
  would have no second implementation. **Revisit when** a second appears — a CLI, a message consumer, or
  a scheduled entry point that needs the same use cases.
- **A Surefire configuration to pin test discovery.** Considered as a guard against the architecture
  suite silently ceasing to run, and rejected: `failIfNoTests` only applies when `-Dtest` is given, so it
  would have been reassuring rather than effective. The gap is real but unaddressed.
- **Marking the backend audit `continue-on-error` so the step could stay.** Rejected for the same
  reason as the point above: a check that cannot fail is worse than no check, because it reads as
  coverage. **Revisit when** an `NVD_API_KEY` secret exists.
