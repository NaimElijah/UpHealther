# Requirements

What UpHealther must do. This document records the requirements the project **currently meets** —
each one is implemented, and the **test** that enforces it is named, so a claim here can be checked
rather than trusted. Eighty-two of the eighty-nine entries below name a test — sixty-one distinct
test classes and files between them. Four of the remaining seven name the command, workflow or script
that *is* the check (NFR-11, NFR-12, NFR-13, NFR-18). The last three — FR-39, NFR-19 and NFR-20 — are
verified by hand and say so, because each is about a rendered width, a colour or an overflow, and jsdom
has no layout engine to observe any of them; §6 records what closing that gap would take.

The test names are the gate. Coverage is reported on every CI run and gates nothing, because a
percentage is satisfied by tests written to move it and says nothing about whether a rule is enforced
— see [ADR-009](../ADRs/ADR-009-test-levels-boundaries-and-naming.md).

For *how* the system is built, see [`../architecture/architecture.md`](../architecture/architecture.md).
For *why* a given solution was chosen, see [`../ADRs/`](../ADRs/).

---

## 1. Purpose and scope

UpHealther is a personal planning and tracking tool for health improvements. It exists because a
"health upgrade" is broader than a habit — it can be a one-off errand, a product swap, a time-boxed
experiment or a goal with a deadline — and habit trackers model only the recurring case.

**In scope:** capturing intended improvements, committing them to dates, tracking whether they are
actually being done, and reflecting on what worked.

**Out of scope:** anything clinical. UpHealther is a lifestyle tool, not a medical application (see
§5.1).

**Users:** one person managing their own upgrades. Every record belongs to exactly one account, and
nothing is shared between accounts.

---

## 2. Functional requirements

### 2.1 Accounts and access

| ID | Requirement | Enforced by |
|---|---|---|
| FR-1 | A visitor can register with a name, email and password, and is signed in immediately | `AuthServiceTest`, `AuthControllerTest` |
| FR-2 | A registered user can sign in with email and password and receive a token | `AuthServiceTest`, `AuthControllerTest` |
| FR-3 | A signed-in user can retrieve their own profile, so a stored token restores a session | `AuthControllerTest`, `AuthContext.test.tsx` |
| FR-4 | An email may be registered once | `AuthServiceTest`, `AuthControllerTest` (422) |
| FR-5 | Every endpoint except registration, login and health checks requires a valid token | `AuthenticatedBoundaryTest` (every protected route), `ProtectedRoute.test.tsx` |

### 2.2 Health areas

| ID | Requirement | Enforced by |
|---|---|---|
| FR-6 | A user can create, read, update and delete their own health areas | `HealthAreaServiceTest`, `HealthAreaControllerTest` |
| FR-7 | An area carries a name and optional description, priority, icon and colour | `HealthAreaServiceTest`, `HealthAreaControllerTest` |
| FR-8 | Deleting an area leaves upgrades filed under it intact | `HealthAreaServiceTest`, `HealthAreaPersistenceIT` |

### 2.3 Upgrades

| ID | Requirement | Enforced by |
|---|---|---|
| FR-9 | A user can create an upgrade of any of the eight kinds: habit, one-time action, product replacement, routine, goal, experiment, learning task, medical/preventive | `FrontendEnumContractTest`, `UpgradeControllerTest` |
| FR-10 | An upgrade carries a title and type, and optionally an area, description, difficulty, planned start, target end, motivation and success criteria | `HealthUpgradeTest`, `UpgradeServiceTest` |
| FR-11 | A user can list their upgrades, narrowed by status, type, area or difficulty | `UpgradeServiceTest`, `UpgradeControllerTest` |
| FR-12 | A user can move an upgrade through its lifecycle: plan, activate, pause, complete, abandon, reschedule | `HealthUpgradeTest`, `UpgradeServiceTest`, `UpgradeControllerTest` |
| FR-13 | A user can edit an upgrade's descriptive fields at any point in its lifecycle | `HealthUpgradeTest`, `UpgradeServiceTest` |
| FR-14 | A user can delete an upgrade | `UpgradeServiceTest`, `UpgradeControllerTest` |
| FR-15 | An upgrade's response carries its tracking configuration, so a list view needs no second call | `UpgradeDtoSerializationTest`, `UpgradeControllerTest` |

### 2.4 Tracking and progress

| ID | Requirement | Enforced by |
|---|---|---|
| FR-16 | A user can configure how an upgrade is measured: boolean, numeric, rating or free text | `TrackingServiceTest`, `TrackingConfigControllerTest` |
| FR-17 | A numeric configuration can carry a target value and a unit | `TrackingServiceTest`, `TrackingConfigControllerTest` |
| FR-18 | A user can log progress for an upgrade on a given day | `TrackingServiceTest`, `ProgressControllerTest` |
| FR-19 | A user can log progress for every active upgrade in one pass | `DailyCheckinPage.test.tsx` — but see §6 |
| FR-20 | A user can read an upgrade's progress history, newest first | `TrackingServiceTest`, `ProgressEntryPersistenceIT` |
| FR-21 | A user can read today's and the last seven days' progress across all upgrades | `TrackingServiceTest`, `ProgressEntryPersistenceIT` |
| FR-22 | A user can see an upgrade's current and longest streak | `StreakCalculatorTest`, `TrackingServiceTest`, `ProgressControllerTest` |

### 2.5 Reflections and reminders

| ID | Requirement | Enforced by |
|---|---|---|
| FR-23 | A user can write a reflection about an upgrade — ratings for difficulty and benefit, and notes on what worked, what did not, and what to change | `ReflectionServiceTest`, `ReflectionControllerTest` |
| FR-24 | A user can read an upgrade's reflections, newest first | `ReflectionServiceTest`, `ReflectionControllerTest` |
| FR-25 | A user can attach reminders to an upgrade, each with a time and a day-of-week filter | `ReminderServiceTest`, `ReminderControllerTest`, `ReminderTest` |
| FR-26 | A user can reschedule, enable, disable and delete a reminder | `ReminderServiceTest`, `ReminderControllerTest` |

### 2.6 Dashboard and notifications

| ID | Requirement | Enforced by |
|---|---|---|
| FR-27 | A user can see, in one request, their active, planned, due-today, overdue and recently completed upgrades, their weekly completion rate, their streaks and per-area counts | `DashboardAggregationServiceTest`, `DashboardControllerTest` |
| FR-28 | A user is notified when an upgrade is created, planned, activated, paused, completed or abandoned, when a reflection is added, and when a streak milestone is reached | `NotificationEventListenerTest` |
| FR-29 | A user is notified when an active upgrade passes its target date | `UpgradeOverdueSchedulerTest` |
| FR-30 | A user with active upgrades and nothing logged is nudged once a day | `NotificationSchedulerTest` |
| FR-31 | A user's reminders fire at the configured time and day | `NotificationSchedulerTest`, `ReminderTest` |
| FR-32 | Notifications arrive in real time on a connected client, and are readable afterwards regardless | `StompNotificationPushAdapterTest`, `NotificationServiceTest`, `NotificationProvider.test.tsx` |
| FR-33 | A user can read their fifty most recent notifications, see an unread count, and mark one or all as read | `NotificationServiceTest`, `NotificationControllerTest` |

### 2.7 Appearance

| ID | Requirement | Enforced by |
|---|---|---|
| FR-34 | A user can set the interface to a light theme, a dark theme, or to follow the operating system | `ThemeToggle`, `ThemeToggle.test.tsx` |
| FR-35 | A user who is following the operating system sees the interface change when that preference changes, without reloading | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-36 | A user's explicit choice overrides the operating system's preference until they change it | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-37 | The theme control is reachable before signing in | `LoginPage.test.tsx` |
| FR-38 | A page's content grows with the browser window, up to one cap chosen for readability | `PageContainer`, `PageContainer.test.tsx` |
| FR-39 | A dialog fits the window at any size: its heading stays put and only its body scrolls | `Modal` — checked by hand, see §6 |
| FR-40 | A dialog announces itself as a dialog named by its title, takes focus on open, confines Tab to its own controls, restores focus on close, and marks the page behind it inert | `Modal`, `Modal.test.tsx`, [ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md) |
| FR-41 | A health area whose stored icon cannot be drawn is shown with the default icon, and editing it does not write the undrawable value back | `areaIconGlyph`, `isIconGlyph`, `areaIcon.test.ts` |

---

## 3. Business rules and invariants

| ID | Rule | Enforced by |
|---|---|---|
| BR-1 | An upgrade is created in `IDEA` and its status changes only through a named transition — never by assignment | `HealthUpgrade` (no setters), `HealthUpgradeTest` |
| BR-2 | Legal transitions are: `IDEA → PLANNED → ACTIVE ⇄ PAUSED`; `ACTIVE → COMPLETED`; any non-final state `→ ABANDONED`; `ABANDONED --reschedule--> PLANNED` | `HealthUpgrade`, `HealthUpgradeTest` |
| BR-3 | `COMPLETED` is terminal — it cannot be reactivated, paused or rescheduled | `HealthUpgradeTest`, `UpgradeServiceTest`, `UpgradeControllerTest` |
| BR-4 | An upgrade must always have an owner, a title and a type | `HealthUpgradeTest`, `UpgradeServiceTest` |
| BR-5 | A user may have at most **three** `HARD` upgrades active at once, checked on every route into a running HARD upgrade | `UpgradeSchedulingServiceTest`, `UpgradeServiceTest`, `UpgradeControllerTest` |
| BR-6 | At most one progress entry exists per upgrade per date; a second is refused as a conflict | `ProgressEntryPersistenceIT` (the constraint by name), `TrackingServiceTest`, `ProgressControllerTest` (409) |
| BR-7 | Whether an entry counts as successful is decided by the server from the tracking configuration, not by the client | `ProgressEvaluationService`, `ProgressEvaluationServiceTest` |
| BR-8 | A numeric entry counts only when its unit agrees with the target's; an unstated unit is read as the configured one | `ProgressEvaluationServiceTest` |
| BR-9 | A streak counts consecutive days; a day not yet logged does not break it | `StreakCalculator`, `StreakCalculatorTest` |
| BR-10 | A streak milestone is announced every seventh day, not every day | `TrackingServiceTest` (7, 14, 21, 70 against 1, 6, 8, 13, 69 — and zero) |
| BR-11 | An overdue upgrade is announced once, however many times the sweep rediscovers it | `NotificationServiceTest`, `NotificationEventListenerTest` |
| BR-12 | A reminder with no day filter fires every day; an unrecognisable day is rejected, never ignored | `ReminderTest`, `ReminderServiceTest`, `ReminderControllerTest` |
| BR-13 | Reflections are append-only — there is no edit or delete path | `ReflectionServiceTest` (asserted against the public surface), `ReflectionControllerTest` |
| BR-14 | Concurrent edits to an upgrade are refused rather than silently merged | `UpgradePersistenceIT`, `GlobalExceptionHandlerTest` |
| BR-15 | A record is visible only to its owner; another user's record is reported as absent, never as forbidden | `UpgradePersistenceIT`, `HealthAreaPersistenceIT`, `ProgressEntryPersistenceIT`, and every `*ControllerTest` |
| BR-16 | A field stored in a bounded column is refused at the boundary when it exceeds that bound, and the response names the field | `ColumnBoundContractTest` (bound vs. column), `UpgradeControllerTest`, `HealthAreaControllerTest`, `ProgressControllerTest`, `TrackingConfigControllerTest` |
| BR-17 | A password is at least 8 characters and at most 72, refused by the API rather than only by the browser | `AuthControllerTest` |

BR-16 exists because the alternative is a 500. Each bound is taken from the column the field lands in
(`V1__init_schema.sql`), so the two cannot drift apart in the direction that matters: `@Size` counts
UTF-16 code units where `VARCHAR(n)` counts characters, which makes the boundary *stricter* than the
column for an astral-plane character such as an emoji, and never looser. Fields stored in `TEXT`
columns are deliberately unbounded — nothing rejects them downstream, so a ceiling there is a product
decision rather than a defect, and §6 records it as open.

BR-17's upper bound is BCrypt's, not a policy: `BCryptPasswordEncoder` reads 72 bytes and silently
drops the rest, so a longer password and its own prefix would be the same password and nobody would be
told. The bound cannot close that gap completely — it counts UTF-16 code units where BCrypt counts
bytes — but it removes the case anyone will actually hit. The minimum applies at registration, which is
the only route by which a password reaches the system; the seeded demo account is inserted as a hash by
Flyway and never passes through it.

---

## 4. Non-functional requirements

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-1 | Authentication is stateless: a signed token, no server session | `AuthenticatedBoundaryTest`, `SecurityConfig` (`SessionCreationPolicy.STATELESS`) |
| NFR-2 | Passwords are stored only as BCrypt hashes; a raw password never leaves the registration call | `AuthServiceTest` (the real BCrypt encoder), `AuthControllerTest` (no password field in any response) |
| NFR-3 | The signing secret is supplied by configuration and must be at least 256 bits, or the application refuses to start | `JwtTokenProviderTest` |
| NFR-4 | A token is valid for 24 hours and is not refreshable | `JwtTokenProviderTest`, `app.jwt.expiration` |
| NFR-5 | The user behind a token is re-loaded on every request, so a deleted account stops working immediately | `JwtAuthenticationFilterTest` |
| NFR-6 | The application never logs personal data deliberately: event publication logs the type and timestamp only, and a trace id identifies a request rather than a person. The one exception is the stack trace of an unexpected 5xx, logged in full so the fault is diagnosable and withheld from the client | `SpringDomainEventPublisher`, `GlobalExceptionHandlerTest` |
| NFR-7 | Every failure maps to a defined HTTP status: 404 not found, 422 rule violation, 409 conflict, 401 rejected credentials, 400 invalid input (a failed constraint, an unbindable body, a parameter that will not convert), 403 denied, and the status Spring defines for every other framework exception (405, 415, 406, …). Only a genuine server fault is a 500, and it carries no detail beyond the status and the trace id that finds its log line | `GlobalExceptionHandlerTest`, every `*ControllerTest`, [ADR-006](../ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md) |
| NFR-8 | The database schema is owned by migrations; the application refuses to start against a schema that does not match its entities | `ApplicationContextIT`, Flyway + `ddl-auto: validate` |
| NFR-9 | Layering is enforced mechanically, not by convention: the domain stays framework-free, the application depends on no adapter, contexts form an acyclic graph | `HexagonalArchitectureTest` (eleven rules) |
| NFR-10 | The frontend's mirrored enums cannot drift from the backend's | `FrontendEnumContractTest` |
| NFR-11 | The unit test suite runs without a database | `mvn test` — needs neither a database nor Docker |
| NFR-12 | Every push and pull request is built, tested, linted, and the shipped frontend dependencies audited | `.github/workflows/ci.yml` |
| NFR-13 | The whole stack starts with one command | `docker-compose up --build` |
| NFR-14 | List endpoints resolve related data in batch rather than per row | `TrackingServiceTest`, `NotificationSchedulerTest` |
| NFR-15 | Time-dependent behaviour reads an injected clock, so it is testable and timezone-explicit | `UpgradeOverdueSchedulerTest`, `NotificationSchedulerTest`, `TrackingServiceTest`, `ReflectionServiceTest` |
| NFR-16 | A user's theme choice survives a reload and is applied before the first paint, so the page never flashes the wrong theme | `bootScript.test.tsx`, `ThemeProvider.test.tsx` |
| NFR-17 | The interface remains usable where browser storage is blocked or `matchMedia` is unavailable | `ThemeProvider`, `ThemeProvider.test.tsx` |
| NFR-18 | Every colour in the interface is a semantic token, so no component can hard-code one that survives a theme change | `frontend/scripts/check-colours.mjs`, `.github/workflows/ci.yml` |
| NFR-19 | Text meets a 4.5:1 contrast ratio and control boundaries 3:1, in both themes | the token values in `frontend/src/index.css` — computed, not automatically re-checked; see §6 |
| NFR-20 | The interface does not scroll horizontally at any window width from 320px upward, whatever a user has stored in it | the shell's `min-w-0` floors and the truncation rules on every user-supplied string ([ADR-005](../ADRs/ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md)) — checked by hand at 320, 360, 486, 684, 1040 and 1540px, see §6 |
| NFR-21 | Every log line written while serving a request, running a scheduled job or handling a STOMP frame carries the same trace id; the id is returned as an `X-Trace-Id` response header and on the error body, and an inbound W3C `traceparent` is continued rather than replaced | `RequestCorrelationTest`, `CorrelationIT`, `StompTracingChannelInterceptorTest`, `ObservabilityConfig` ([ADR-007](../ADRs/ADR-007-request-correlation-through-micrometer-tracing.md)) |
| NFR-22 | Log output is one JSON object per line in a container and Boot's readable pattern locally, and a line in either format carries its trace id | `LogOutputFormatTest`, `logback-spring.xml` ([ADR-010](../ADRs/ADR-010-structured-logging-and-a-level-policy.md)) |
| NFR-23 | Every state-changing use case and every authentication outcome records who attempted what, against which record, and whether it was allowed — including the attempts that were refused, and never claiming as allowed work whose transaction then rolled back | `AuditTrailTest`, `LoggingAuditTrailTest`, `AuditCommitIT`, `UpgradeServiceTest`, `AuthServiceTest` ([ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md)) |
| NFR-24 | An audit entry cannot carry personal data, because it has nowhere to put any: every field is an enum or an identifier, and a refused login is recorded with no subject at all | `AuditEventTest`, `AuthServiceTest` |
| NFR-25 | Every scheduled run records how long it took, whether it finished, and what it did, so a job that has stopped working is distinguishable from one with nothing to do | `JobMetricsTest`, `NotificationSchedulerTest`, `UpgradeOverdueSchedulerTest` |
| NFR-26 | A real-time push that cannot be delivered degrades to the stored notification and is reported, rather than failing the work that raised it | `StompNotificationPushAdapterTest` |
| NFR-27 | Liveness and readiness are answerable separately, so "restart the process" and "stop routing to it" are distinguishable, and both images declare a health-check | `ActuatorEndpointsIT`, `backend/Dockerfile`, `docker-compose.yml` |
| NFR-28 | Latency, error rate, saturation, connection-pool depth and the domain's own counters are readable from one scrape endpoint, and no metric tag is unbounded | `ActuatorEndpointsIT`, `LoggingAuditTrailTest`, `JobMetricsTest` ([ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md)) |
| NFR-29 | The actuator surface is closed by name: only health, info and the metrics scrape answer, and an endpoint that would expose configuration or process memory does not | `ActuatorEndpointsIT` (env, heapdump, loggers, beans, mappings, configprops, threaddump) |
| NFR-30 | A request that fails shows the user the trace id that finds it in the log, from the error body or the response header, and offers none when the request never reached the server | `apiError.test.ts`, `ErrorState.test.tsx` |
| NFR-31 | A render-time error shows a recoverable message rather than blanking the page | `ErrorBoundary.test.tsx` |

---

## 5. Non-goals

These are deliberate. Re-proposing one needs a reason that has changed.

- **5.1 — Not a medical application.** UpHealther gives no medical advice, diagnosis or treatment, and
  no feature may imply otherwise. It is a lifestyle planning tool and says so in the README and in the
  licence's warranty disclaimer.
- **5.2 — Not multi-tenant or shared.** There is no sharing, no accountability partner, no team view.
  Every record belongs to one account.
- **5.3 — Not horizontally scaled.** Real-time push uses an in-memory broker, so it reaches only
  clients connected to the instance that raised the notification. Running more than one instance needs
  a broker relay first — see `architecture.md`, "Known constraints".
- **5.4 — Not an open platform.** There is no public API, no API keys, no third-party integration, and
  the code is source-available rather than open source ([ADR-003](../ADRs/ADR-003-proprietary-source-available-licensing.md)).

---

## 6. Open questions

Undecided, and owned by the repository owner.

- **An anonymous request to a protected endpoint is answered 403, not 401.** No
  `AuthenticationEntryPoint` is configured, so Spring Security's `Http403ForbiddenEntryPoint` answers
  it. FR-5 is met either way — the request is refused — but 401 is the semantically correct status and
  a client cannot distinguish "not signed in" from "not allowed". `AuthenticatedBoundaryTest` asserts
  the behaviour as it is. Changing it is a one-line configuration change and a breaking change for any
  client branching on the status, so it is a decision rather than a fix.

- **The daily check-in logs every active upgrade, including the ones left untouched.**
  `DailyCheckinPage`'s own documentation says an untouched upgrade is "left unlogged rather than
  recorded as missed", but `handleSubmit` posts an entry for each one. With BR-6 allowing a single
  entry per upgrade per day, an upgrade nobody filled in is therefore recorded as a failure that
  cannot be corrected until tomorrow. `DailyCheckinPage.test.tsx` asserts the behaviour and flags the
  disagreement; which of the two is right — submit-everything or submit-what-was-touched — is a
  product decision, and FR-19 does not settle it.

- **The frontend has no accessibility or visual-regression gate.** Contrast ratios in the theme were
  computed by hand; nothing re-checks them when a colour changes, and jsdom cannot — it has no layout
  engine. Closing this needs a real browser in CI; [ADR-004](../ADRs/ADR-004-frontend-test-harness.md)
  records why that was deferred. FR-39 and NFR-20 land in exactly this gap: no test in the suite can
  observe a width, a wrap or an overflow, so both were verified by hand and neither is enforced.
- **Nothing verifies that a browser honours the dialog's `inert`.** The focus trap and the inert page
  behind it are built and tested ([ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md)),
  but jsdom implements `inert` not at all, so the tests pin that the attribute is set and cleared on
  the right nodes and nothing further. This is the same gap as the entry above and closes with it.
  Two smaller residuals go with it: focus falls back to `<body>` when the element that opened a dialog
  unmounted along with it, which the health-areas delete path does; and a toast raised while a dialog
  is open goes inert with the rest of the page, so it is painted above the dialog and cannot be
  dismissed.
- **The backend has no dependency vulnerability audit.** OWASP dependency-check needs an `NVD_API_KEY`
  secret; ADR-002 records why a check that cannot fail was judged worse than none.
- **No ceiling on the free-text fields.** `description`, `motivation`, `successCriteria`, `note`,
  `whatWorked`, `whatDidNotWork` and `nextAdjustment` are `TEXT`, so BR-16 leaves them alone: nothing
  downstream rejects them and there is no 500 to prevent. What is left is that a single request can
  store as much prose as the servlet container will accept. How long a reflection may be is a product
  decision, not a defect, and nobody has taken it.
- **Whether `DataIntegrityViolationException` should be mapped at all.** BR-16 removes the known route
  to it, but it stays unmapped, so any constraint a DTO annotation cannot express still surfaces as a
  500. Mapping it centrally is not one decision but several — a unique violation, a foreign-key
  violation and a not-null violation do not deserve the same status, and `DuplicateProgressException`
  already shadows the first of them.
- **`UpgradeType.PROTOCOL` is deprecated but retained** for rows that may already carry it. Removing it
  needs confirmation that no stored row uses it.
- **No governing jurisdiction is named in the licence** — ADR-003 flags this as the first thing to add
  if the project ever becomes commercially significant.
- **Requirement priorities and delivery order are not recorded here.** Everything above is already
  built, so nothing has needed ranking yet.

---

UpHealther is still evolving and expanding, and so are its requirements — this document records what
the project meets today, not the limit of what it will do.
