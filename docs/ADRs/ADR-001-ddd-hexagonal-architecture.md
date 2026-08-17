# ADR-001: Adopt Domain-Driven Design + Hexagonal (Ports & Adapters) for the backend

- **Status:** Accepted
- **Date:** 2026-07-09
- **Scope:** `backend/` (Spring Boot 3 / Java 21). The React frontend is unaffected.

## Context

The backend already used a package-by-feature layout with `api / application / domain / infrastructure`
folders, but the hexagonal boundaries were **nominal, not enforced**:

- Domain entities were JPA `@Entity` classes with business logic mixed in.
- The `User` entity implemented Spring Security's `UserDetails` (domain → framework).
- The stateless domain services were Spring `@Service` beans, and one injected a JPA repository
  (a domain → infrastructure dependency).
- Application services depended on concrete Spring Data `JpaRepository` interfaces — there were no ports.
- Bounded contexts called each other through concrete service classes, and web DTOs leaked across contexts.
- Notifications used `SimpMessagingTemplate` directly; the domain event publisher was a concrete component.

Nothing prevented these boundaries from eroding further over time.

## Decision

Adopt a **pragmatic Hexagonal (Ports & Adapters) + DDD** architecture, enforced by tests.

### Key choices
1. **Pragmatic purity.** The domain is framework-free **except** that entities keep their JPA mapping
   annotations and remain the persistence model (no separate POJO + persistence-mapper duplication).
2. **Enforced with ArchUnit** in a single Maven module (no multi-module split).
3. **Canonical package layout**, identical for every bounded context
   (`auth, user, healtharea, upgrade, tracking, reflection, reminder, dashboard, notification`):

   | Package | Contents |
   |---|---|
   | `domain/model` | JPA entities, enums, value objects |
   | `domain/service` | pure (framework-free) domain services |
   | `domain/port/out` | outbound ports (repository SPI, push, event publisher) |
   | `application` | `@Service` / `@Transactional` use-case orchestration; `*BeansConfig` wiring for domain services |
   | `application/port/in` | inbound ports (use-case / query interfaces) + command/result records |
   | `adapter/in/web` | controllers, request records, response DTOs, web mappers |
   | `adapter/in/event`, `adapter/in/scheduling` | event listener / scheduler driving adapters (notification) |
   | `adapter/out/persistence` | Spring Data `*JpaRepository` + the adapter implementing the outbound port |
   | `adapter/out/messaging` | STOMP push adapter |

   Cross-cutting `common/` is split into a framework-free shared kernel (`common/domain/event`,
   `common/domain/exception`) and cross-cutting adapters (`common/adapter/out/event`,
   `common/adapter/in/event`, `common/adapter/in/web`), plus `common/security` and `common/websocket`.

### Patterns
- **Outbound ports + adapters:** each aggregate has a `domain/port/out` repository port implemented by an
  `adapter/out/persistence` adapter that delegates to a (package-private) Spring Data `*JpaRepository`.
- **Inbound ports:** cross-context reads go through another context's `application/port/in` query ports and
  return **domain objects**, never a repository or a foreign web DTO.
- **Domain state machine stays on the entity** (`HealthUpgrade.plan/activate/…`); the max-concurrent-HARD
  invariant is a **pure** domain service that receives the current count as a parameter.
- **Security:** `SecurityUser` (a `UserDetails` wrapper) keeps Spring Security out of the `User` entity.
- **Events & real-time push are outbound ports:** `DomainEventPublisher` (interface) +
  `SpringDomainEventPublisher`; `NotificationPushPort` + `StompNotificationPushAdapter`. The publisher still
  delegates to Spring's `ApplicationEventPublisher`, so `@TransactionalEventListener(AFTER_COMMIT)` is intact.

### Enforced rules (`backend/src/test/.../architecture/HexagonalArchitectureTest.java`, ArchUnit)
1. **Domain purity (strict):** `..domain..` must not depend on `org.springframework..`, `..adapter..`, or
   `..application..`. (`jakarta.persistence` and `lombok` are intentionally allowed on entities.)
2. **Application not on outbound adapters (strict):** `..application..` must not depend on `..adapter.out..`.
3. **Spring Data confinement (strict):** only `..adapter.out.persistence..` may depend on Spring Data JPA.
4. **Outbound ports are interfaces (strict):** everything in `..domain.port.out..` is an interface.
5. **Bounded-context isolation:** contexts may depend on each other only through shared surfaces —
   `common..`, another context's `..domain.model..`, `..application.port.in..`, or its published
   `..adapter.in.web..` DTOs.

### Pragmatic exception
An application service **may** use its **own** context's `adapter/in/web` response DTOs (so most services
still return/build their own DTOs). Only cross-context DTO leaks and any domain → adapter dependency are
forbidden. `upgrade` and `dashboard` go further (services return domain; dedicated web mappers build the
DTOs) because their DTOs compose data from other contexts.

## Consequences

**Positive**
- Boundaries are now verified on every `mvn test`; violations fail the build.
- The domain and application layers are framework-boundary-clean; every side effect (persistence, events,
  real-time push) sits behind a port, making the core easy to unit-test without Spring.
- Contexts are decoupled — cross-context traffic flows only through inbound ports returning domain objects.

**Trade-offs**
- More types (ports, adapters, mappers, command/result records) — deliberate, for testability and isolation.
- The pragmatic DTO exception means the web boundary is not 100% textbook; response DTOs can still originate
  in application services within their own context.
- Entities remain JPA-annotated (pragmatic choice), so the domain model is not persistence-ignorant.

**Neutral / notes**
- No behavior or wire-format change was introduced by the migration; the PostgreSQL schema is unchanged
  (Flyway-owned, Hibernate `ddl-auto: validate`). The migration landed as a sequence of independently
  merged checkpoints.
