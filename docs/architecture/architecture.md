# Architecture

The system as it actually is, at the time of writing. This record describes **what** is built and
**how the pieces fit**; it does not argue for any of it. The reasoning, the alternatives that were
rejected, and the conditions that would make us revisit a choice live in [`docs/ADRs/`](../ADRs/).

---

## Architecture overview

UpHealther is a three-process web application: a React single-page app, a Spring Boot HTTP API,
and a PostgreSQL database. There is no message broker, no cache, no third-party service and no second
backend — every piece of state lives in the one database, and every side effect the API performs is
either a write to it or a message pushed to a connected browser.

The browser talks to the API over HTTP for everything it reads and writes, and holds an additional
WebSocket connection that the API uses to push notifications as they are raised. Both travel to the
same origin: a proxy in front of the SPA forwards `/api` and `/ws` to the API, so the browser makes no
cross-origin request in the default setup.

Inside the API, the code is organised as nine bounded contexts over a ports-and-adapters core. The
domain and application layers know nothing about HTTP, JPA or Spring's event bus; each of those
arrives through an adapter. The boundaries are not a convention — they are checked on every build by
an ArchUnit suite, which is the authority on what the layering permits.

> **Diagram:** [System context](arch-diagrams/README.md#1-system-context) — the three
> processes, the browser, and what each connection carries.

---

## Components

### Runtime processes

| Component | What it is | Responsible for |
|---|---|---|
| **Frontend** | nginx serving a Vite production build (container port 80, published on 3000) | Serving the SPA, and proxying `/api` and `/ws` to the backend so the browser stays same-origin |
| **Backend** | Spring Boot 3.2 on Java 21, executable jar (port 8080) | The whole domain: authentication, the upgrade lifecycle, tracking, reflections, reminders, the dashboard read model, notifications, and the scheduled jobs |
| **Database** | PostgreSQL 15 (port 5432, volume `postgres_data`) | Every piece of persistent state. Schema owned by Flyway |

In development the nginx container is not involved: the Vite dev server serves the SPA on 3000 and
performs the same `/api` and `/ws` proxying itself, so the app behaves identically on both sides.

### Backend bounded contexts

Nine contexts, each owning its own vocabulary, plus a cross-cutting `common`.

| Context | Owns | Shape |
|---|---|---|
| `upgrade` | `HealthUpgrade` — the core aggregate and its state machine | Full: domain, application, adapters |
| `tracking` | `TrackingConfig`, `ProgressEntry`, streak and success evaluation | Full |
| `reflection` | `Reflection` — periodic written reviews | Full |
| `reminder` | `Reminder` and its day-of-week schedule | Full |
| `notification` | `Notification` — the inbox and the real-time push | Full, and the only context with a messaging adapter |
| `healtharea` | `HealthArea` — the groupings upgrades are filed under | Full |
| `user` | `User` — identity and the stored password hash | Full |
| `auth` | Registration, login, and issuing tokens | Two-layer: orchestrates over `user`, owns no aggregate |
| `dashboard` | The composed dashboard read model | Two-layer: reads through other contexts' ports, persists nothing |
| `common` | Cross-cutting: the `DomainEvent` marker, shared exceptions, the event-publisher port, the global exception handler, JWT security and the WebSocket configuration | Not a context; a shared kernel plus cross-cutting adapters |

`auth` and `dashboard` being two-layer is deliberate, not drift — see ADR-002.

### Frontend modules

| Module | Responsible for |
|---|---|
| `src/api/` | One axios instance and a thin function per endpoint. The instance attaches the JWT and turns a 401 into a logout; every call goes through it |
| `src/contexts/` | `AuthProvider` owns the session; `NotificationProvider` owns the notification list, the STOMP connection and the toasts |
| `src/hooks/` | `useAuth` and `useNotifications` — typed context readers that fail loudly outside their provider |
| `src/router/` | The route table, and `ProtectedRoute`, which gates every authenticated page |
| `src/pages/` | One component per route |
| `src/components/` | `ui/` primitives, `upgrade/` cards and badges, `notifications/` bell, dropdown, items and toasts, `layout/` navbar and sidebar |
| `src/types/` | Hand-written mirrors of the backend's response shapes and enums |

---

## Communication

Five distinct mechanisms, each used for one thing:

**1. HTTP, browser to API.** Every read and write. JSON in and out, JWT bearer token in the
`Authorization` header. Same-origin through the proxy, so no CORS preflight in the default setup; the
`CORS_ALLOWED_ORIGINS` policy exists only for deployments that split the origins.

**2. STOMP over WebSocket, API to browser.** One-way in practice: the browser connects and subscribes,
the API pushes. The handshake itself is unauthenticated — a browser cannot set headers on it — so the
JWT travels in the STOMP `CONNECT` frame and is validated by a channel interceptor, which attaches a
principal named by user id. Messages are routed to `/user/queue/notifications`, which the broker
resolves per session using that principal.

The broker is Spring's in-memory simple broker. There is no external broker, so a push reaches only
clients connected to *this* instance — see "Known constraints" below.

**3. In-process domain events, context to context.** A context that changes state publishes a record
implementing `DomainEvent` through the `DomainEventPublisher` port. The adapter behind it delegates to
Spring's `ApplicationEventPublisher`, so consumers can bind to the transaction:
`@TransactionalEventListener(AFTER_COMMIT)` means a notification is never raised for a write that then
rolls back. Events raised outside a transaction — by the scheduled sweeps — use a plain `@EventListener`,
because there is no commit for a transactional listener to wait for.

Events are asynchronous only in the sense of being decoupled; they are delivered in the same process,
and nothing is queued or persisted between publisher and consumer.

**4. Direct calls through inbound ports, context to context.** Where a context needs to *read* another,
it calls that context's `application/port/in` interface and receives domain objects — never a
repository, never a web DTO. `TrackingService` confirms upgrade ownership this way before recording
progress.

**5. Scheduled invocation.** Three cron-driven jobs drive the core with no request behind them: the
overdue sweep, the daily check-in nudge, and the per-minute reminder dispatch.

### Which contexts depend on which

Derived from the imports, not from intent:

> **Diagram:** [Bounded-context map](arch-diagrams/README.md#2-bounded-context-map) —
> generated from the imports, so it is what the code does rather than what was intended.

`upgrade`, `user` and `healtharea` depend on no other context. The graph is acyclic, and ArchUnit
fails the build if that stops being true.

The one edge that is not obvious is the absent one. An upgrade's response carries its tracking
configuration, which would mean `upgrade → tracking` — and `tracking → upgrade` already exists for
ownership checks. Instead `upgrade` declares `UpgradeTrackingSummaryPort` describing what it needs, in
a record it owns, and `tracking` supplies it through a composition adapter. Both arrows run the same
way. ADR-002 records why.

---

## Data flow

### A write, end to end

Recording a day's progress, which touches most of the machinery:

> **Diagram:** [Request lifecycle](arch-diagrams/README.md#6-request-lifecycle) —
> logging progress, from the browser through to the pushed notification.

Three things in that flow are easy to miss:

- **Ownership is checked by the query, not by a guard.** Every repository lookup is scoped by user id,
  so a row belonging to someone else is indistinguishable from one that does not exist and surfaces as
  404. There is no role model and no per-resource authorization layer.
- **The server decides completion.** The `completed` flag the client sends is advisory; when the
  upgrade has a tracking configuration the entry is re-evaluated against the target, so streaks and
  rates cannot be inflated by a client.
- **The push happens after commit, and the notification is stored either way.** A browser that was
  offline sees it on its next fetch.

### A read, end to end

`GET /api/dashboard` is the widest read. `DashboardAggregationService` calls four inbound ports —
upgrades, progress entries, streaks, health areas — buckets the upgrades by status and date, computes
the weekly rate, and returns a `DashboardView` of domain objects. The web mapper then turns it into the
response, reusing the upgrade context's own mapper so the embedded upgrades are identical to what
`/api/upgrades` returns, and mapping each distinct upgrade once so a single batched query resolves
every tracking configuration rather than one per upgrade.

### The record's lifetime

A `HealthUpgrade` is created as an `IDEA` and moves only through methods on the aggregate, each of
which guards its transition:

> **Diagram:** [The upgrade lifecycle](arch-diagrams/README.md#5-the-upgrade-lifecycle) —
> every legal transition of the aggregate's state machine.

`COMPLETED` is terminal. `ABANDONED` is not — rescheduling revives it. An upgrade occupies one of the
three concurrent HARD slots only while `ACTIVE`, which is why the limit is checked both when activating
one and when promoting a running one to HARD.

### Time-driven flows

| Job | Default schedule | What it does |
|---|---|---|
| `UpgradeOverdueScheduler` | daily 08:00 | Publishes `UpgradeOverdueDetected` for every active upgrade past its target date. The notification listener creates at most one notification per upgrade, so the repeated detection does not repeat the alert |
| `NotificationScheduler.notifyDailyCheckin` | daily 18:00 | Nudges users who have active upgrades and have logged nothing today, at most once a day |
| `NotificationScheduler.dispatchReminders` | every minute | Fires the reminders due this minute. Due-ness is decided by the `Reminder` aggregate; the upgrades behind the due ones are loaded in one batch |

All three read the clock through an injected `java.time.Clock`, which is what makes them testable
without waiting.

---

## External dependencies and integration points

**There are no third-party APIs.** Nothing leaves the deployment: no payment provider, no email or
push service, no analytics, no AI service.

| Dependency | Used for | How it is reached |
|---|---|---|
| **PostgreSQL 15** | All persistent state | JDBC from the backend only. Credentials from `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` |
| **Flyway** | Schema ownership and migration on startup | Embedded in the backend. `V1` schema, `V2` demo seed, `V3` demo password fix, `V4` notifications |
| **Browser WebSocket** | Real-time notification delivery | The `/ws` STOMP endpoint, proxied by nginx (or Vite in development) |
| **Browser Notification API** | Desktop notifications when the tab is backgrounded | Optional, permission-gated, and skipped entirely where the API is unavailable |

Integration points a maintainer will need:

- **`/actuator/health`** — the backend's health endpoint, unauthenticated, and what the compose
  health-check polls.
- **Environment variables** — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` are required in any
  real deployment; `CORS_ALLOWED_ORIGINS` and `VITE_API_URL` only matter when frontend and API are on
  different origins. The cron expressions are overridable per environment. `.env.example` lists them
  all with safe values.
- **CI** — GitHub Actions on every push and PR to `main` and `dev`: the backend runs `mvn verify`
  against a PostgreSQL service container, the frontend lints, tests, audits its shipped dependencies
  and builds.

---

## Structural decisions not visible from the file layout

**The dependency arrow always points inward.** `adapter → application → domain`, never the reverse.
No class in `application` imports anything from `adapter`, in either direction. This is what allows the
whole core to be unit-tested without Spring, and it is checked rather than trusted.

**The domain is framework-free, with one deliberate exception.** Domain classes import no Spring, no
Jackson, no servlet, no bean validation. They do carry JPA mapping annotations, because the entities
*are* the persistence model — there is no separate POJO-plus-mapper layer. That trade-off is ADR-001's,
and ADR-002 reaffirms it.

**Pure domain services carry no stereotype annotation.** `StreakCalculator`,
`ProgressEvaluationService` and `UpgradeSchedulingService` are plain classes, hand-registered as beans
by `*BeansConfig` classes in the application layer. A dropped `@Bean` there is invisible to unit tests,
which is why `ApplicationContextIT` exists.

**Business invariants live on the aggregate, not in services.** `HealthUpgrade` has no setters: status
moves only through its transition methods, descriptive fields only through `updateDetails`, difficulty
only through `changeDifficulty`, and instances are created through a validating factory. A service
orchestrates — load, call the domain method, save, publish — and does not decide.

The one invariant that cannot live there is the max-concurrent-HARD rule, because it needs a count
across the user's *other* upgrades, which one aggregate cannot see. It is a pure domain service that
receives the count as a parameter, and the application layer performs the counting query.

**Spring Data is confined to the persistence adapters, and its interfaces are package-private.**
Everything else depends on a repository port. Swapping the store would mean rewriting one package per
context and nothing else.

**Every request record and response DTO belongs to the web adapter.** Application services take command
records from `application/port/in` and return domain objects; a `*WebMapper` per context translates in
both directions. A wire-format change therefore cannot reach a use-case signature. `UpgradeTrackingConfigDto`
duplicating `TrackingConfigDto` field for field is the visible cost, and is intentional.

**Optimistic locking is on one aggregate.** Only `HealthUpgrade` carries `@Version`; a concurrent edit
returns 409. Nothing else is version-checked, because nothing else is edited from two places at once.

**Exception to HTTP status is decided in exactly one class.** Controllers and services throw domain
exceptions and never build a status by hand. The mapping is pinned by a test.

**The frontend's types are hand-written, not generated.** `src/types/index.ts` mirrors the backend's
DTOs and enums by hand. The **enums** are checked: `FrontendEnumContractTest` reads that file and fails
the build if any mirrored union stops matching its backend enum, which is what stops an unbindable
value reaching the UI. The **DTO shapes** are not checked — a renamed or retyped field still drifts
silently, and only a type generated from the API contract would close that gap.

### Known constraints

Stated because they are load-bearing, not because they are problems yet:

- **A single backend instance.** The STOMP broker is in-memory, so a notification pushed by one
  instance reaches only the clients connected to that instance. Notifications are persisted, so a
  client on another instance sees them on its next fetch rather than instantly. Running more than one
  instance needs a real broker relay first.
- **`mvn test` needs no database; `mvn verify` does.** The integration tests boot the application
  against a real PostgreSQL. Keeping the unit suite database-free is a constraint worth preserving.
- **The backend has no dependency vulnerability audit.** OWASP dependency-check cannot populate its
  database without an `NVD_API_KEY`. ADR-002 records why a check that always fails, or one that cannot
  fail, was judged worse than none.

---

## Where the reasoning lives

| Question | Record |
|---|---|
| What does all of this look like? | [`arch-diagrams/`](arch-diagrams/README.md) — seven diagrams, outside in |
| What is the system supposed to do, and what is it deliberately not doing? | [`docs/requirements/requirements.md`](../requirements/requirements.md) |
| Why DDD + hexagonal at all, and why JPA entities as the domain model? | [ADR-001](../ADRs/ADR-001-ddd-hexagonal-architecture.md) |
| Why the `upgrade`/`tracking` dependency is inverted; why events moved out of `common`; what the ten ArchUnit rules cover; what was rejected and when to revisit | [ADR-002](../ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) |
| Why the frontend tests with Vitest rather than Jest; why Vitest is pinned to 3; why there is still no accessibility gate | [ADR-004](../ADRs/ADR-004-frontend-test-harness.md) |
| Day-to-day conventions when changing backend code | [`backend/CLAUDE.md`](../../backend/CLAUDE.md) |
| Day-to-day conventions when changing frontend code | [`frontend/CLAUDE.md`](../../frontend/CLAUDE.md) |
| How to run, test and deploy it | [`README.md`](../../README.md) |
