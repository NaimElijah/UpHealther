# Requirements

What UpHealther must do. This document records the requirements the project **currently meets** —
each one is implemented, and where it is enforced in code the enforcing class or test is named, so a
claim here can be checked rather than trusted.

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
| FR-1 | A visitor can register with a name, email and password, and is signed in immediately | `AuthService.register` |
| FR-2 | A registered user can sign in with email and password and receive a token | `AuthService.login` |
| FR-3 | A signed-in user can retrieve their own profile, so a stored token restores a session | `AuthController.me`, `AuthContext` |
| FR-4 | An email may be registered once | `AuthService.register` → `BusinessRuleException` |
| FR-5 | Every endpoint except registration, login and health checks requires a valid token | `SecurityConfig` |

### 2.2 Health areas

| ID | Requirement | Enforced by |
|---|---|---|
| FR-6 | A user can create, read, update and delete their own health areas | `HealthAreaService` |
| FR-7 | An area carries a name and optional description, priority, icon and colour | `HealthArea`, `HealthAreaRequest` |
| FR-8 | Deleting an area leaves upgrades filed under it intact | `HealthAreaService.delete` |

### 2.3 Upgrades

| ID | Requirement | Enforced by |
|---|---|---|
| FR-9 | A user can create an upgrade of any of the eight kinds: habit, one-time action, product replacement, routine, goal, experiment, learning task, medical/preventive | `UpgradeType`, `FrontendEnumContractTest` |
| FR-10 | An upgrade carries a title and type, and optionally an area, description, difficulty, planned start, target end, motivation and success criteria | `HealthUpgrade.create` |
| FR-11 | A user can list their upgrades, narrowed by status, type, area or difficulty | `UpgradeService.findAll` |
| FR-12 | A user can move an upgrade through its lifecycle: plan, activate, pause, complete, abandon, reschedule | `HealthUpgrade`, `HealthUpgradeTest` |
| FR-13 | A user can edit an upgrade's descriptive fields at any point in its lifecycle | `HealthUpgrade.updateDetails` |
| FR-14 | A user can delete an upgrade | `UpgradeService.delete` |
| FR-15 | An upgrade's response carries its tracking configuration, so a list view needs no second call | `UpgradeWebMapper`, `UpgradeDtoSerializationTest` |

### 2.4 Tracking and progress

| ID | Requirement | Enforced by |
|---|---|---|
| FR-16 | A user can configure how an upgrade is measured: boolean, numeric, rating or free text | `TrackingType` |
| FR-17 | A numeric configuration can carry a target value and a unit | `TrackingConfig` |
| FR-18 | A user can log progress for an upgrade on a given day | `TrackingService.recordProgress` |
| FR-19 | A user can log progress for every active upgrade in one pass | `DailyCheckinPage` |
| FR-20 | A user can read an upgrade's progress history, newest first | `TrackingService.getProgress` |
| FR-21 | A user can read today's and the last seven days' progress across all upgrades | `TrackingService.getTodayProgress`, `getWeekProgress` |
| FR-22 | A user can see an upgrade's current and longest streak | `StreakCalculator`, `StreakCalculatorTest` |

### 2.5 Reflections and reminders

| ID | Requirement | Enforced by |
|---|---|---|
| FR-23 | A user can write a reflection about an upgrade — ratings for difficulty and benefit, and notes on what worked, what did not, and what to change | `ReflectionService.create` |
| FR-24 | A user can read an upgrade's reflections, newest first | `ReflectionService.getForUpgrade` |
| FR-25 | A user can attach reminders to an upgrade, each with a time and a day-of-week filter | `ReminderService`, `ReminderDays` |
| FR-26 | A user can reschedule, enable, disable and delete a reminder | `ReminderService` |

### 2.6 Dashboard and notifications

| ID | Requirement | Enforced by |
|---|---|---|
| FR-27 | A user can see, in one request, their active, planned, due-today, overdue and recently completed upgrades, their weekly completion rate, their streaks and per-area counts | `DashboardAggregationService`, `DashboardAggregationServiceTest` |
| FR-28 | A user is notified when an upgrade is created, planned, activated, paused, completed or abandoned, when a reflection is added, and when a streak milestone is reached | `NotificationEventListener`, `NotificationEventListenerTest` |
| FR-29 | A user is notified when an active upgrade passes its target date | `UpgradeOverdueScheduler` |
| FR-30 | A user with active upgrades and nothing logged is nudged once a day | `NotificationScheduler.notifyDailyCheckin` |
| FR-31 | A user's reminders fire at the configured time and day | `NotificationScheduler.dispatchReminders`, `ReminderTest` |
| FR-32 | Notifications arrive in real time on a connected client, and are readable afterwards regardless | `StompNotificationPushAdapter`, `NotificationService` |
| FR-33 | A user can read their fifty most recent notifications, see an unread count, and mark one or all as read | `NotificationService` |

### 2.7 Appearance

| ID | Requirement | Enforced by |
|---|---|---|
| FR-34 | A user can set the interface to a light theme, a dark theme, or to follow the operating system | `ThemeToggle`, `ThemeToggle.test.tsx` |
| FR-35 | A user who is following the operating system sees the interface change when that preference changes, without reloading | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-36 | A user's explicit choice overrides the operating system's preference until they change it | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-37 | The theme control is reachable before signing in | `LoginPage`, `RegisterPage` |

---

## 3. Business rules and invariants

| ID | Rule | Enforced by |
|---|---|---|
| BR-1 | An upgrade is created in `IDEA` and its status changes only through a named transition — never by assignment | `HealthUpgrade` (no setters), `HealthUpgradeTest` |
| BR-2 | Legal transitions are: `IDEA → PLANNED → ACTIVE ⇄ PAUSED`; `ACTIVE → COMPLETED`; any non-final state `→ ABANDONED`; `ABANDONED --reschedule--> PLANNED` | `HealthUpgrade`, `HealthUpgradeTest` |
| BR-3 | `COMPLETED` is terminal — it cannot be reactivated, paused or rescheduled | `HealthUpgrade.activate/pause/reschedule` |
| BR-4 | An upgrade must always have an owner, a title and a type | `HealthUpgrade.create` |
| BR-5 | A user may have at most **three** `HARD` upgrades active at once, checked on every route into a running HARD upgrade | `UpgradeSchedulingService`, `UpgradeSchedulingServiceTest` |
| BR-6 | At most one progress entry exists per upgrade per date; a second is refused as a conflict | `TrackingService`, unique constraint on `progress_entries` |
| BR-7 | Whether an entry counts as successful is decided by the server from the tracking configuration, not by the client | `ProgressEvaluationService`, `ProgressEvaluationServiceTest` |
| BR-8 | A numeric entry counts only when its unit agrees with the target's; an unstated unit is read as the configured one | `ProgressEvaluationService.unitsAreComparable` |
| BR-9 | A streak counts consecutive days; a day not yet logged does not break it | `StreakCalculator`, `StreakCalculatorTest` |
| BR-10 | A streak milestone is announced every seventh day, not every day | `TrackingService.recordProgress` |
| BR-11 | An overdue upgrade is announced once, however many times the sweep rediscovers it | `NotificationService.createOncePerUpgrade` |
| BR-12 | A reminder with no day filter fires every day; an unrecognisable day is rejected, never ignored | `ReminderDays`, `ReminderTest` |
| BR-13 | Reflections are append-only — there is no edit or delete path | `ReflectionService` |
| BR-14 | Concurrent edits to an upgrade are refused rather than silently merged | `@Version` on `HealthUpgrade` |
| BR-15 | A record is visible only to its owner; another user's record is reported as absent, never as forbidden | every repository query is user-scoped; `ResourceNotFoundException` |

---

## 4. Non-functional requirements

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-1 | Authentication is stateless: a signed token, no server session | `SecurityConfig` (`SessionCreationPolicy.STATELESS`) |
| NFR-2 | Passwords are stored only as BCrypt hashes; a raw password never leaves the registration call | `SecurityConfig.passwordEncoder`, `AuthService` |
| NFR-3 | The signing secret is supplied by configuration and must be at least 256 bits, or the application refuses to start | `JwtTokenProvider` |
| NFR-4 | A token is valid for 24 hours and is not refreshable | `app.jwt.expiration` |
| NFR-5 | The user behind a token is re-loaded on every request, so a deleted account stops working immediately | `JwtAuthenticationFilter` |
| NFR-6 | Personal data is never written to logs; event publication logs the type and timestamp only | `SpringDomainEventPublisher` |
| NFR-7 | Every failure maps to a defined HTTP status: 404 not found, 422 rule violation, 409 conflict, 400 invalid input, 403 denied | `GlobalExceptionHandler`, `GlobalExceptionHandlerTest` |
| NFR-8 | The database schema is owned by migrations; the application refuses to start against a schema that does not match its entities | Flyway + `ddl-auto: validate` |
| NFR-9 | Layering is enforced mechanically, not by convention: the domain stays framework-free, the application depends on no adapter, contexts form an acyclic graph | `HexagonalArchitectureTest` (ten rules) |
| NFR-10 | The frontend's mirrored enums cannot drift from the backend's | `FrontendEnumContractTest` |
| NFR-11 | The unit test suite runs without a database | `mvn test` |
| NFR-12 | Every push and pull request is built, tested, linted, and the shipped frontend dependencies audited | `.github/workflows/ci.yml` |
| NFR-13 | The whole stack starts with one command | `docker-compose up --build` |
| NFR-14 | List endpoints resolve related data in batch rather than per row | `UpgradeWebMapper.toDtos`, `TrackingConfigQuery.findByUpgradeIds` |
| NFR-15 | Time-dependent behaviour reads an injected clock, so it is testable and timezone-explicit | `Clock` bean, scheduler tests |
| NFR-16 | A user's theme choice survives a reload and is applied before the first paint, so the page never flashes the wrong theme | the boot script in `frontend/index.html`, `ThemeProvider` |
| NFR-17 | The interface remains usable where browser storage is blocked or `matchMedia` is unavailable | `ThemeProvider`, `ThemeProvider.test.tsx` |
| NFR-18 | Every colour in the interface is a semantic token, so no component can hard-code one that survives a theme change | `frontend/scripts/check-colours.mjs`, `.github/workflows/ci.yml` |
| NFR-19 | Text meets a 4.5:1 contrast ratio and control boundaries 3:1, in both themes | the token values in `frontend/src/index.css` — computed, not automatically re-checked; see §6 |

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

- **The frontend has no accessibility or visual-regression gate.** Contrast ratios in the theme were
  computed by hand; nothing re-checks them when a colour changes, and jsdom cannot — it has no layout
  engine. Closing this needs a real browser in CI; [ADR-004](../ADRs/ADR-004-frontend-test-harness.md)
  records why that was deferred.
- **The backend has no dependency vulnerability audit.** OWASP dependency-check needs an `NVD_API_KEY`
  secret; ADR-002 records why a check that cannot fail was judged worse than none.
- **An unbindable request body returns 500 rather than 400** — tracked as
  [issue #22](https://github.com/NaimElijah/UpHealther/issues/22).
- **`UpgradeType.PROTOCOL` is deprecated but retained** for rows that may already carry it. Removing it
  needs confirmation that no stored row uses it.
- **No governing jurisdiction is named in the licence** — ADR-003 flags this as the first thing to add
  if the project ever becomes commercially significant.
- **Requirement priorities and delivery order are not recorded here.** Everything above is already
  built, so nothing has needed ranking yet.

---

UpHealther is still evolving and expanding, and so are its requirements — this document records what
the project meets today, not the limit of what it will do.
