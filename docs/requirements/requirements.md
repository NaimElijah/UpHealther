# Requirements

What UpHealther must do. This document records the requirements the project **currently meets** —
each one is implemented, and the **test** that enforces it is named, so a claim here can be checked
rather than trusted. A hundred and twelve of the hundred and twenty entries below name a test —
ninety-two distinct test classes and files between them. Five of the remaining eight name the command,
workflow or script that *is* the check (NFR-11, NFR-12, NFR-13, NFR-18, NFR-48). The last three —
FR-39, NFR-19 and NFR-20 — are verified by hand and say so, because each is about a rendered width, a
colour or an overflow, and jsdom has no layout engine to observe any of them; §6 records what closing
that gap would take.

**A functional requirement is met in the shipped product, not merely in the API.** There is no public
API (§5.4), so "a user can" means through the interface. Where the implementation falls short of an
entry — in the interface or anywhere else — the entry is not weakened to match. Its *Enforced by* cell
says **Known deviation** and links the issue that tracks the gap; the tests named there enforce the
part that holds. Twenty-nine entries carry one today, and the change that closes an issue takes its
marker out.

**IDs are permanent.** Tests, code comments, migrations and ADRs cite them, so an ID is never
renumbered, reused or moved to another prefix. A new entry takes the next free number and is filed
under its subject, which is why the numbers in a table need not run in order.

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

**One carve-out, and only one.** An installation has administrators, who can list the accounts on
it, switch one off or back on, and grant or revoke the role. That is the whole of it: an
administrator gains *paths*, never *rows*. Every query is scoped by the account that owns the
record, for an administrator exactly as for anybody else, so there is no way — not a guarded one,
no way at all — to read another person's health records
([ADR-016](../ADRs/ADR-016-roles-read-from-the-database-on-every-request.md)).

---

## 2. Functional requirements

### 2.1 Accounts and access

| ID | Requirement | Enforced by |
|---|---|---|
| FR-1 | A visitor can register with a name, email and password, and is signed in immediately | `AuthServiceTest`, `AuthControllerTest` |
| FR-2 | A registered user can sign in with email and password and receive a token | `AuthServiceTest`, `AuthControllerTest` |
| FR-3 | A signed-in user can retrieve their own profile, so a reloaded page learns who is signed in once the refresh cookie has renewed the session (NFR-38) | `AuthControllerTest`, `AuthContext.test.tsx` |
| FR-4 | An email may be registered once. Addresses are compared trimmed and case-insensitively, the database refuses any other stored form, and a registration that loses a race for an address is refused like any other duplicate | `AuthServiceTest`, `AuthControllerTest` (422), `EmailAddressTest`, `UserPersistenceIT`, `RegistrationRaceIT` |
| FR-5 | Every endpoint requires a valid access token except six: registration and sign-in; refresh and sign-out, which act on the refresh cookie and are guarded as NFR-37 states; the actuator, which answers only what NFR-29 leaves open; and the WebSocket handshake, whose STOMP CONNECT is authenticated instead (NFR-32). A request without a token, or with one the server refuses, is answered 401 with a `WWW-Authenticate: Bearer` challenge and the API's error body | `AuthenticatedBoundaryTest` (every protected route, and exactly those six public), `JwtAuthenticationFilterTest`, `ErrorBodySecurityHandlersTest`, `ProtectedRoute.test.tsx` ([ADR-014](../ADRs/ADR-014-unauthenticated-requests-are-401-with-the-api-error-body.md)) |
| FR-48 | A signed-in user can sign out on this device. A sign-out that fails says so and leaves them signed in, rather than appearing to have worked while the session lives on (NFR-35 is how immediately it takes effect) | `AuthControllerTest`, `AuthSessionServiceTest`, `AuthContext.test.tsx` — **Known deviation:** [#97](https://github.com/NaimElijah/UpHealther/issues/97), the failure is not shown below 640px |

### 2.2 Health areas

| ID | Requirement | Enforced by |
|---|---|---|
| FR-6 | A user can create, read, update and delete their own health areas | `HealthAreaServiceTest`, `HealthAreaControllerTest` — **Known deviation:** [#97](https://github.com/NaimElijah/UpHealther/issues/97), unreachable below 768px |
| FR-7 | An area carries a name and optional description, priority, icon and colour | `HealthAreaServiceTest`, `HealthAreaControllerTest` — **Known deviation:** [#91](https://github.com/NaimElijah/UpHealther/issues/91), a priority cannot be set, and an edit clears one |
| FR-8 | Deleting an area leaves upgrades filed under it intact | `HealthAreaServiceTest`, `HealthAreaPersistenceIT` |

### 2.3 Upgrades

| ID | Requirement | Enforced by |
|---|---|---|
| FR-9 | A user can create an upgrade of any of the eight kinds: habit, one-time action, product replacement, routine, goal, experiment, learning task, medical/preventive | `FrontendEnumContractTest`, `UpgradeControllerTest` |
| FR-10 | An upgrade carries a title and type, and optionally an area, description, difficulty, planned start, target end, motivation and success criteria | `HealthUpgradeTest`, `UpgradeServiceTest` — **Known deviation:** [#89](https://github.com/NaimElijah/UpHealther/issues/89), success criteria and both dates cannot be set from the interface |
| FR-11 | A user can list their upgrades, narrowed by one of status, type, area or difficulty | `UpgradeServiceTest`, `UpgradeControllerTest` — **Known deviation:** [#90](https://github.com/NaimElijah/UpHealther/issues/90), the interface filters by status only |
| FR-12 | A user can move an upgrade through its lifecycle: plan, activate, pause, complete, abandon, reschedule | `HealthUpgradeTest`, `UpgradeServiceTest`, `UpgradeControllerTest` — **Known deviation:** [#88](https://github.com/NaimElijah/UpHealther/issues/88), [#89](https://github.com/NaimElijah/UpHealther/issues/89), Plan always fails, and abandon and reschedule have no control |
| FR-13 | A user can edit an upgrade's descriptive fields at any point in its lifecycle | `HealthUpgradeTest`, `UpgradeServiceTest` — **Known deviation:** [#89](https://github.com/NaimElijah/UpHealther/issues/89), the interface has no edit |
| FR-14 | A user can delete an upgrade, and with it everything recorded against it: its tracking configuration, progress, reminders and reflections. Its notifications stay, detached from it | `UpgradeServiceTest`, `UpgradeControllerTest`, `UpgradePersistenceIT` — **Known deviation:** [#89](https://github.com/NaimElijah/UpHealther/issues/89), the interface has no delete |
| FR-15 | An upgrade's response carries its tracking configuration, so a list view needs no second call | `UpgradeDtoSerializationTest`, `UpgradeControllerTest` |

### 2.4 Tracking and progress

| ID | Requirement | Enforced by |
|---|---|---|
| FR-16 | A user can configure how an upgrade is measured: boolean, numeric, rating or free text. An upgrade has at most one configuration, and saving another replaces it | `TrackingServiceTest`, `TrackingConfigControllerTest` |
| FR-17 | A numeric configuration can carry a target value and a unit | `TrackingServiceTest`, `TrackingConfigControllerTest` |
| FR-18 | A user can log progress for an upgrade on a given day | `TrackingServiceTest`, `ProgressControllerTest` — **Known deviation:** [#95](https://github.com/NaimElijah/UpHealther/issues/95), the interface dates an entry in UTC |
| FR-19 | A user can log progress for every active upgrade in one pass | `DailyCheckinPage.test.tsx` — but see §6 — **Known deviation:** [#95](https://github.com/NaimElijah/UpHealther/issues/95), [#55](https://github.com/NaimElijah/UpHealther/issues/55), entries are dated in UTC, and untouched upgrades are logged too |
| FR-20 | A user can read an upgrade's progress history, newest first | `TrackingServiceTest`, `ProgressEntryPersistenceIT` — **Known deviation:** [#92](https://github.com/NaimElijah/UpHealther/issues/92), the interface lists oldest first |
| FR-21 | A user can read today's and the last seven days' progress across all upgrades | `TrackingServiceTest`, `ProgressEntryPersistenceIT` — **Known deviation:** [#97](https://github.com/NaimElijah/UpHealther/issues/97), unreachable below 768px |
| FR-22 | A user can see an upgrade's current and longest streak | `StreakCalculatorTest`, `TrackingServiceTest`, `ProgressControllerTest` |

### 2.5 Reflections and reminders

| ID | Requirement | Enforced by |
|---|---|---|
| FR-23 | A user can write a reflection about an upgrade — ratings for difficulty and benefit, and notes on what worked, what did not, and what to change | `ReflectionServiceTest`, `ReflectionControllerTest` |
| FR-24 | A user can read an upgrade's reflections, newest first | `ReflectionServiceTest`, `ReflectionControllerTest` — **Known deviation:** [#92](https://github.com/NaimElijah/UpHealther/issues/92), the interface lists oldest first |
| FR-25 | A user can attach reminders to an upgrade, each with a time and a day-of-week filter | `ReminderServiceTest`, `ReminderControllerTest`, `ReminderTest` |
| FR-26 | A user can reschedule, enable, disable and delete a reminder | `ReminderServiceTest`, `ReminderControllerTest` — **Known deviation:** [#89](https://github.com/NaimElijah/UpHealther/issues/89), reschedule, enable and disable have no control |

### 2.6 Dashboard and notifications

| ID | Requirement | Enforced by |
|---|---|---|
| FR-27 | A user can see, in one request, their active, planned, due-today, overdue and recently completed upgrades, their weekly completion rate, their streaks and per-area counts | `DashboardAggregationServiceTest`, `DashboardControllerTest` — **Known deviation:** [#93](https://github.com/NaimElijah/UpHealther/issues/93), the per-area counts are never shown |
| FR-28 | A user is notified when an upgrade is created, planned, activated, paused, completed or abandoned, when a reflection is added, and when a streak milestone is reached | `NotificationEventListenerTest` |
| FR-29 | A user is notified when an active upgrade passes its target date | `UpgradeOverdueSchedulerTest` — **Known deviation:** [#89](https://github.com/NaimElijah/UpHealther/issues/89), the interface cannot set a target date |
| FR-30 | A user with active upgrades and nothing logged is nudged once a day | `NotificationSchedulerTest` |
| FR-31 | A user's reminders fire at the configured time and day | `NotificationSchedulerTest`, `ReminderTest` |
| FR-32 | Notifications arrive in real time on a connected client, and are readable afterwards regardless | `StompNotificationPushAdapterTest`, `NotificationServiceTest`, `NotificationProvider.test.tsx` |
| FR-33 | A user can read their fifty most recent notifications, see an unread count that covers every notification rather than only the fifty listed, and mark one or all as read | `NotificationServiceTest`, `NotificationControllerTest` — **Known deviation:** [#94](https://github.com/NaimElijah/UpHealther/issues/94), the interface counts only the fifty fetched |
| FR-49 | A user can opt in to desktop notifications, which are raised only while the tab is in the background — with the tab in view, the in-page notice already says it | `NotificationProvider.test.tsx` |

FR-27's terms are the server's, and `DashboardAggregationServiceTest` pins each. An upgrade is **due
today** when it is active and today falls between its start and its target end, either of which may be
open; it is **overdue** when it is active and past its target end. **Recently completed** is the five
most recently completed, newest first. The **weekly completion rate** is the percentage of entries
dated in the last seven days, today included, that count as successful (BR-7), and zero when there are
none. **Streaks** are reported for active upgrades only, and an area's **counts** include every area
the user has, with zeroes, but no row for upgrades filed under none.

### 2.7 Appearance and accessibility

| ID | Requirement | Enforced by |
|---|---|---|
| FR-34 | A user can set the interface to a light theme, a dark theme, or to follow the operating system | `ThemeToggle`, `ThemeToggle.test.tsx` |
| FR-35 | A user who is following the operating system sees the interface change when that preference changes, without reloading | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-36 | A user's explicit choice overrides the operating system's preference until they change it | `ThemeProvider`, `ThemeProvider.test.tsx` |
| FR-37 | The theme control is reachable before signing in | `LoginPage.test.tsx` |
| FR-38 | A page's content grows with the browser window, up to one of two caps chosen for readability: `wide` for lists, grids and dashboards, `narrow` for reading and form pages | `PageContainer`, `PageContainer.test.tsx` ([ADR-005](../ADRs/ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md)) |
| FR-39 | A dialog fits the window at any size: its heading stays put and only its body scrolls | `Modal` — checked by hand, see §6 |
| FR-40 | A dialog announces itself as a dialog named by its title, takes focus on open, confines Tab to its own controls, restores focus on close, and marks the page behind it inert | `Modal`, `Modal.test.tsx`, [ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md) |
| FR-41 | A health area whose stored icon cannot be drawn is shown with the default icon, and editing it does not write the undrawable value back | `areaIconGlyph`, `isIconGlyph`, `areaIcon.test.ts` |

### 2.8 Account administration

The role model behind every entry here is [ADR-016](../ADRs/ADR-016-roles-read-from-the-database-on-every-request.md).

| ID | Requirement | Enforced by |
|---|---|---|
| FR-42 | An administrator can list the accounts on the installation, a page of at most a hundred at a time and oldest first, seeing each one's role and whether it is switched on — and nothing about what it owns | `AdminUserServiceTest`, `AdminUserControllerTest`, `UserPersistenceIT` (the order) — **Known deviation:** [#97](https://github.com/NaimElijah/UpHealther/issues/97), unreachable below 768px |
| FR-43 | An administrator can switch an account off and back on. Switching it off ends every session it holds and destroys nothing it owns, so switching it back on restores the account exactly as it was | `AdminUserServiceTest`, `AdminUserControllerTest` — **Known deviation:** [#83](https://github.com/NaimElijah/UpHealther/issues/83), an open WebSocket outlives the switch-off |
| FR-44 | An administrator can grant and revoke the administrator role. The change is read from the account on its next request, so it takes effect without signing that person out | `AdminUserServiceTest`, `AdminUserControllerTest` |
| FR-45 | A fresh installation can be given its first administrator through configuration, by account id and only while no *enabled* administrator exists — so it cannot silently re-promote somebody after a deliberate demotion, cannot be claimed by whoever registers an address first, and still recovers an installation whose administrators have disabled each other | `AdminBootstrapRunnerTest` |
| FR-46 | The account administration screen is offered only to an administrator, in the navigation and at its route, and an administrator's own row offers no controls at all — the server refuses a self-directed change, and a control that can only fail is worse than none | `RequireRole.test.tsx`, `Sidebar.test.tsx`, `AdminUsersPage.test.tsx` |
| FR-47 | Disabling an account and changing a role are confirmed before they happen, and the confirmation says what the change actually does; a change that fails says so rather than appearing to have worked | `AdminUsersPage.test.tsx` |

---

## 3. Business rules and invariants

| ID | Rule | Enforced by |
|---|---|---|
| BR-1 | An upgrade is created in `IDEA` and its status changes only through a named transition — never by assignment | `HealthUpgrade` (no setters), `HealthUpgradeTest` |
| BR-2 | Legal transitions are: `IDEA → PLANNED → ACTIVE ⇄ PAUSED`; `ACTIVE → COMPLETED`; any non-final state `→ ABANDONED`; `ABANDONED --reschedule--> PLANNED`. Rescheduling moves the dates of an upgrade in any state but `COMPLETED`, and only from `ABANDONED` does it change the status too | `HealthUpgrade`, `HealthUpgradeTest` |
| BR-3 | `COMPLETED` is terminal — it cannot be reactivated, paused or rescheduled | `HealthUpgradeTest`, `UpgradeServiceTest`, `UpgradeControllerTest` |
| BR-4 | An upgrade must always have an owner, a title and a type | `HealthUpgradeTest`, `UpgradeServiceTest` |
| BR-5 | A user may have at most **three** `HARD` upgrades active at once, checked on every route into a running HARD upgrade | `UpgradeSchedulingServiceTest`, `UpgradeServiceTest`, `UpgradeControllerTest` |
| BR-6 | At most one progress entry exists per upgrade per date; a second is refused as a conflict | `ProgressEntryPersistenceIT` (the constraint by name), `TrackingServiceTest`, `ProgressControllerTest` (409) |
| BR-7 | When an upgrade has a tracking configuration, whether an entry counts as successful is decided by the server from that configuration, not by the client. Without one there is no target to judge against, and the entry keeps the `completed` value its caller sent | `ProgressEvaluationService`, `ProgressEvaluationServiceTest`, `TrackingServiceTest` |
| BR-8 | A numeric entry counts only when its unit agrees with the target's; an unstated unit is read as the configured one | `ProgressEvaluationServiceTest` |
| BR-9 | A streak counts consecutive days; a day not yet logged does not break it | `StreakCalculator`, `StreakCalculatorTest` |
| BR-10 | A streak milestone is announced every seventh day, not every day | `TrackingServiceTest` (7, 14, 21, 70 against 1, 6, 8, 13, 69 — and zero) — **Known deviation:** [#101](https://github.com/NaimElijah/UpHealther/issues/101), any entry logged while the streak sits on a multiple of seven re-announces it |
| BR-11 | An overdue upgrade is announced once, however many times the sweep rediscovers it | `NotificationServiceTest`, `NotificationEventListenerTest` |
| BR-12 | A reminder with no day filter fires every day; an unrecognisable day is rejected when it is sent, never ignored | `ReminderTest`, `ReminderServiceTest`, `ReminderControllerTest` |
| BR-13 | Reflections are append-only — there is no edit or delete path of their own. They go only with the upgrade they belong to (FR-14) | `ReflectionServiceTest` (asserted against the public surface), `ReflectionControllerTest`, `UpgradePersistenceIT` |
| BR-14 | Concurrent edits to an upgrade are refused rather than silently merged | `UpgradePersistenceIT`, `GlobalExceptionHandlerTest` |
| BR-15 | A record is visible only to its owner; another user's record is reported as absent, never as forbidden | `UpgradePersistenceIT`, `HealthAreaPersistenceIT`, `ProgressEntryPersistenceIT`, and every `*ControllerTest` |
| BR-16 | A field stored in a bounded column is refused at the boundary when it exceeds that bound, and the response names the field | `ColumnBoundContractTest` (bound vs. column), `UpgradeControllerTest`, `HealthAreaControllerTest`, `ProgressControllerTest`, `TrackingConfigControllerTest`, `AuthControllerTest` |
| BR-17 | A password is at least 8 characters and at most 72, refused by the API rather than only by the browser | `AuthControllerTest` |
| BR-18 | An upgrade can be filed only under a health area its owner owns; a foreign area and a missing one are refused alike, with no hint which it was | `UpgradeServiceTest`, `HealthAreaServiceTest` |
| BR-19 | A rating is a whole number from 1 to 5: a progress entry's rating, and a reflection's difficulty and benefit ratings | `ProgressControllerTest`, `ReflectionControllerTest` |
| BR-20 | A numeric progress value is never negative | `ProgressControllerTest` |
| BR-21 | Success is decided per tracking type: a boolean entry counts when marked complete; a numeric one when its value reaches the target in a comparable unit, and never when the configuration has no target; a rating when it is at least 3, the midpoint of the scale; a text entry when its note is not blank | `ProgressEvaluationServiceTest` |
| BR-22 | Changing a tracking configuration does not rescore entries already logged; it judges only what is logged afterwards | `TrackingServiceTest` |

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

BR-18 is checked when an area is set, not on every write: an update that leaves `areaId` as it was does
not re-check it, so an upgrade filed before the rule existed stays editable. The rule does not rewrite
such rows — before BR-18, an `areaId` belonging to another user was stored as given.

---

## 4. Non-functional requirements

### 4.1 Sessions and credentials

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-1 | The API holds no HTTP session and no conversational state: a request is authenticated by the credential it carries and nothing else, so any instance can serve any request. The signed-in session is a row the token names, not server memory. The one thing held in memory is the rate limiter's counters, so that limit is per instance (NFR-42, §5.3) | `AuthenticatedBoundaryTest`, `AuthSessionFlowIT`, `SecurityConfig` (`SessionCreationPolicy.STATELESS`) |
| NFR-2 | Passwords are stored only as BCrypt hashes; a raw password never leaves the registration call | `AuthServiceTest` (the real BCrypt encoder), `AuthControllerTest` (no password field in any response) |
| NFR-3 | The token settings are supplied by configuration and validated as the application starts, so a missing one is named at boot rather than surfacing as a null inside the first request that needs it; the signing secret must be at least 256 bits, or the application refuses to start | `JwtTokenProviderTest`, `ApplicationContextIT` |
| NFR-4 | A token names its account by id and the session it belongs to, never an email, and is accepted only under this application's issuer, audience and signing algorithm. It is valid for `app.jwt.access-token-ttl`, plus `app.jwt.clock-skew` so that two hosts disagreeing by a few seconds do not refuse each other's tokens, and is renewed through the refresh credential rather than by signing in again | `JwtTokenProviderTest`, `AuthSessionFlowIT` |
| NFR-5 | The session and the account behind a token are both re-loaded on every request, so signing out or disabling an account stops an already-issued token working on the very next request rather than whenever it would have expired | `BearerTokenAuthenticatorTest`, `AuthSessionFlowIT` — **Known deviation:** [#83](https://github.com/NaimElijah/UpHealther/issues/83), an open WebSocket outlives both |
| NFR-35 | A user can end a session, and ending it takes effect immediately: the access token already issued within it stops working on its next request. Sign-out is per device — it leaves the same account signed in elsewhere | `AuthSessionServiceTest`, `AuthSessionFlowIT`, `AuthControllerTest` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) — **Known deviation:** [#83](https://github.com/NaimElijah/UpHealther/issues/83), an open WebSocket keeps receiving until it reconnects |
| NFR-36 | The long-lived refresh credential is never readable by script and never stored in a form that can be presented: it travels in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie and is held only as a SHA-256 digest. It is replaced on every use. Presenting the credential a rotation replaced, once the grace window has passed, revokes the session and is audited; a credential that neither digest recognises revokes nothing. The cookie is scoped to `/api/auth`, so it is not sent with ordinary API calls, and a session nothing can use any more is deleted by a nightly sweep rather than kept | `AuthSessionTest`, `AuthSessionServiceTest`, `AuthSessionPersistenceIT`, `AuthControllerTest`, `AuthSessionFlowIT`, `AuthSessionCleanupSchedulerTest` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) — **Known deviation:** [#87](https://github.com/NaimElijah/UpHealther/issues/87), `Secure` is off unless a deployment turns it on |
| NFR-37 | The two endpoints that act on the cookie alone cannot be driven from another site: the cookie is `SameSite=Strict`, and both additionally require a header that a cross-site form post cannot set, refusing the request before any session is read | `AuthControllerTest`, `AuthSessionFlowIT` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) |
| NFR-38 | The access token is never written to browser storage: it is held in memory for the life of the tab, so it cannot be read by injected script and does not outlive the page. A reload restores the session from the refresh cookie instead, and the credentials of an earlier version are removed from storage on load | `tokenStore.test.ts`, `AuthContext.test.tsx`, `client.test.ts` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) |
| NFR-39 | An expired access token is renewed and the request retried, rather than ending the session: the renewal is single-flight within a tab and across tabs, so a burst of parallel calls rotates the refresh credential once. A renewal answered 409, because another tab rotated first, is tried once more. Only a refusal from the renewal itself signs the user out, and a 403 never does | `client.test.ts`, `AuthSessionFlowIT` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) |

| NFR-47 | A session ends when it goes unused for `app.auth.session.idle`, and at `app.auth.session.absolute` however often it is refreshed, so neither an abandoned session nor a busy one lives indefinitely | `AuthSessionTest` ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)) |

### 4.2 Access control and hardening

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-32 | A WebSocket session is authorised frame by frame, not only at CONNECT: a subscription must name the one destination the application pushes to, and a SEND is refused, so a connected session cannot read another session's notifications by naming the destination the broker resolved that session's queue to | `JwtChannelInterceptorTest`, `StompNotificationPushAdapterTest` |
| NFR-33 | An account holds one of two roles, read from the database on every request rather than carried in the token, so granting or revoking ADMIN takes effect on the next request. A role decides which paths answer, never which rows do: the user-scoped queries apply to an administrator exactly as they do to anyone else | `UserTest`, `UserDetailsServiceImplTest`, `BearerTokenAuthenticatorTest`, `AuthenticatedBoundaryTest` ([ADR-016](../ADRs/ADR-016-roles-read-from-the-database-on-every-request.md)) |
| NFR-34 | An account can be switched off without destroying anything it owns: a disabled account cannot sign in, and a token it was already issued stops working on its next request. The refusal costs the same as a wrong password and reads the same on the wire, so it does not disclose that the account exists | `UserTest`, `DisabledAccountAuthenticationTest`, `BearerTokenAuthenticatorTest` |
| NFR-40 | An administrator cannot act on their own account — not disable it, not enable it, not change its role. Each would be unrecoverable from inside the application: the last administrator could lock the installation, or revoke the role nobody is left to grant | `AdminUserServiceTest`, `AdminUserControllerTest` |
| NFR-41 | The administration context has no dependency on any context holding a user's own records, so "an administrator cannot read your health data" is a property of what the code can reach rather than a check somebody remembered to write | `HexagonalArchitectureTest` |
| NFR-42 | Sign-in and registration are rate-limited per client address — the only two endpoints that take a password. Over the limit answers 429 with `Retry-After` and never reaches the application, so a refused attempt costs no password comparison. The limit is per address and never per account, so nobody can lock another person out by failing to sign in as them | `FixedWindowRateLimiterTest`, `RateLimitedSignInTest` ([ADR-017](../ADRs/ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md)) |
| NFR-43 | The address a limit counts against cannot be chosen by the caller: the proxy overwrites `X-Forwarded-For` rather than appending to it, and only the proxy's own address is trusted to set it. IPv6 is counted by its /64, so rotating addresses within one allocation buys no extra allowance | `FixedWindowRateLimiterTest`, `nginx.conf`, `server.tomcat.remoteip.internal-proxies` ([ADR-017](../ADRs/ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md)) |
| NFR-44 | The rate limiter's memory is bounded, so the defence cannot itself be turned into a denial of service by a caller rotating addresses | `FixedWindowRateLimiterTest` ([ADR-017](../ADRs/ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md)) |
| NFR-45 | The page is served under a content security policy that permits one inline script by hash and no inline script by category, so an injected script does not execute. The policy also forbids framing, plugin content and a rewritten base URL. It is sent alongside `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` and `Permissions-Policy`, and every one of these headers is sent on error responses as well as successful ones | `bootScriptCsp.test.ts` ([ADR-018](../ADRs/ADR-018-a-content-security-policy-with-a-hashed-inline-boot-script.md)) |
| NFR-46 | The hash permitting the inline theme script is recomputed from the shipped file by a test, so editing that script without updating the policy fails the build rather than producing a flash of the wrong theme in production only | `bootScriptCsp.test.ts` |

### 4.3 Data, errors and resilience

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-7 | Every failure maps to a defined HTTP status: 404 not found, 422 rule violation, 409 conflict (including a refresh that lost a rotation race, which the client simply retries), 401 rejected credentials or no accepted token, 400 invalid input (a failed constraint, an unbindable body, a parameter that will not convert), 403 authenticated but not allowed, 429 over a rate limit with `Retry-After`, and the status Spring defines for every other framework exception (405, 415, 406, …). Only a genuine server fault is a 500, and it carries no detail beyond the status and the trace id that finds its log line | `GlobalExceptionHandlerTest`, every `*ControllerTest`, `RateLimitedSignInTest` (429), `AuthControllerTest` (the retryable 409), [ADR-006](../ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md) |
| NFR-8 | The database schema is owned by migrations; the application refuses to start against a schema that does not match its entities | `ApplicationContextIT`, Flyway + `ddl-auto: validate` |
| NFR-14 | List endpoints resolve related data in batch rather than per row | `TrackingServiceTest`, `NotificationSchedulerTest` — **Known deviation:** [#99](https://github.com/NaimElijah/UpHealther/issues/99), the dashboard's streaks and the check-in sweep are resolved per row |
| NFR-15 | Time-dependent behaviour reads an injected clock, so it is testable and timezone-explicit | `UpgradeOverdueSchedulerTest`, `NotificationSchedulerTest`, `TrackingServiceTest`, `ReflectionServiceTest` — **Known deviation:** [#51](https://github.com/NaimElijah/UpHealther/issues/51), [#100](https://github.com/NaimElijah/UpHealther/issues/100), timestamps bypass the clock, and no zone is ever chosen |
| NFR-26 | A real-time push that cannot be delivered degrades to the stored notification and is reported, rather than failing the work that raised it | `StompNotificationPushAdapterTest` |
| NFR-49 | A notification is pushed to a connected client only after the transaction that stored it commits, so a client is never told about a row that then rolled back | `NotificationServiceTest` |

### 4.4 Observability and audit

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-6 | The application never logs personal data deliberately: event publication logs the type and timestamp only, and a trace id identifies a request rather than a person. The one exception is the stack trace of an unexpected 5xx, logged in full so the fault is diagnosable and withheld from the client | `SpringDomainEventPublisher`, `GlobalExceptionHandlerTest` |
| NFR-21 | Every log line written while serving a request, running a scheduled job or handling a STOMP frame carries the same trace id; the id is returned as an `X-Trace-Id` response header and on the error body, and an inbound W3C `traceparent` is continued rather than replaced | `RequestCorrelationTest`, `CorrelationIT`, `StompTracingChannelInterceptorTest`, `TraceIdResponseHeaderFilterTest`, `CorrelationIdTest`, `ObservabilityConfigTest` ([ADR-007](../ADRs/ADR-007-request-correlation-through-micrometer-tracing.md)) — **Known deviation:** [#105](https://github.com/NaimElijah/UpHealther/issues/105), a container error dispatch carries no id in the body |
| NFR-22 | Log output is one JSON object per line in a container and Boot's readable pattern locally, and a line in either format carries its trace id | `LogOutputFormatTest`, `logback-spring.xml` ([ADR-010](../ADRs/ADR-010-structured-logging-and-a-level-policy.md)) |
| NFR-23 | Every state-changing use case and every authentication outcome records who attempted what, against which record, and whether it was allowed — including the attempts that were refused, and never claiming as allowed work whose transaction then rolled back | `AuditTrailTest`, `AuditOutcomeTest`, `LoggingAuditTrailTest`, `AuditCommitIT`, `UpgradeServiceTest`, `AuthServiceTest` ([ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md)) — **Known deviation:** [#98](https://github.com/NaimElijah/UpHealther/issues/98), refused refreshes, failed sign-outs, refused CONNECTs and the bootstrap promotion |
| NFR-24 | An audit entry cannot carry personal data, because it has nowhere to put any: every field is an enum or an identifier, and a refused login is recorded with no subject at all | `AuditEventTest`, `AuthServiceTest` |
| NFR-25 | Every scheduled run records how long it took, whether it finished, and what it did, so a job that has stopped working is distinguishable from one with nothing to do | `JobMetricsTest`, `NotificationSchedulerTest`, `UpgradeOverdueSchedulerTest`, `AuthSessionCleanupSchedulerTest` |
| NFR-27 | Liveness and readiness are answerable separately, so "restart the process" and "stop routing to it" are distinguishable, and both images declare a health-check | `ActuatorEndpointsIT`, `backend/Dockerfile`, `docker-compose.yml` ([ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md)) — **Known deviation:** [#75](https://github.com/NaimElijah/UpHealther/issues/75), the frontend's health-check can never pass |
| NFR-28 | Latency, error rate, saturation, connection-pool depth and the domain's own counters are readable from one scrape endpoint, and no metric tag is unbounded | `ActuatorEndpointsIT`, `LoggingAuditTrailTest`, `JobMetricsTest`, `RateLimitInterceptorTest` ([ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md)) |
| NFR-29 | The actuator surface is closed by name: only health, info and the metrics scrape answer, and an endpoint that would expose configuration or process memory does not | `ActuatorEndpointsIT` (env, heapdump, loggers, beans, mappings, configprops, threaddump) ([ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md)) |
| NFR-30 | A request that fails shows the user the trace id that finds it in the log, from the error body or the response header, and offers none when the request never reached the server | `apiError.test.ts`, `ErrorState.test.tsx` — **Known deviation:** [#76](https://github.com/NaimElijah/UpHealther/issues/76), [#96](https://github.com/NaimElijah/UpHealther/issues/96), many mutations fail without a word |

### 4.5 Interface

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-16 | A user's theme choice survives a reload and is applied before the first paint, so the page never flashes the wrong theme | `bootScript.test.tsx`, `ThemeProvider.test.tsx` |
| NFR-17 | The interface remains usable where browser storage is blocked or `matchMedia` is unavailable | `ThemeProvider`, `ThemeProvider.test.tsx` |
| NFR-18 | Every colour in the interface is a semantic token, so no component can hard-code one that survives a theme change | `frontend/scripts/check-colours.mjs`, `.github/workflows/ci.yml` |
| NFR-19 | Text meets a 4.5:1 contrast ratio and control boundaries 3:1, in both themes | the token values in `frontend/src/index.css` — computed, not automatically re-checked; see §6 |
| NFR-20 | The interface does not scroll horizontally at any window width from 320px upward, whatever a user has stored in it | the shell's `min-w-0` floors and the truncation rules on every user-supplied string ([ADR-005](../ADRs/ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md)) — checked by hand at 320, 360, 486, 684, 1040 and 1540px, see §6 |
| NFR-31 | A render-time error shows a recoverable message rather than blanking the page | `ErrorBoundary.test.tsx` |

### 4.6 Build, test and structure

| ID | Requirement | Enforced by |
|---|---|---|
| NFR-9 | Layering is enforced mechanically, not by convention: the domain stays framework-free, the application depends on no adapter, contexts form an acyclic graph, and the administration context is fenced off as NFR-41 states | `HexagonalArchitectureTest` (twelve rules) ([ADR-001](../ADRs/ADR-001-ddd-hexagonal-architecture.md), [ADR-002](../ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)) |
| NFR-10 | The frontend's mirrored enums cannot drift from the backend's | `FrontendEnumContractTest` |
| NFR-11 | The unit test suite runs without a database | `mvn test` — needs neither a database nor Docker |
| NFR-12 | Every push and pull request is built, tested, linted, and the shipped frontend dependencies audited, and fails when the generated architecture diagrams have drifted from their source | `.github/workflows/ci.yml` |
| NFR-13 | The whole stack starts with one command | `docker-compose up --build` |
| NFR-48 | The integration suite starts its own PostgreSQL in a container and never runs against a database it finds already running, so a local one on the same port cannot make it pass against the wrong schema | `mvn verify`, through `support/PostgresIT.java`, which every `*IT` extends ([ADR-008](../ADRs/ADR-008-testcontainers-for-the-integration-test-database.md)) |

---

## 5. Non-goals

These are deliberate. Re-proposing one needs a reason that has changed.

- **5.1 — Not a medical application.** UpHealther gives no medical advice, diagnosis or treatment, and
  no feature may imply otherwise. It is a lifestyle planning tool and says so in the README and in the
  licence's warranty disclaimer.
- **5.2 — Not multi-tenant or shared.** There is no sharing, no accountability partner, no team view.
  Every record belongs to one account. Administration is not an exception to this: an administrator
  manages *accounts* and cannot read what an account owns, which is why the capability is stated in
  §1 as a carve-out to the user model rather than as a hole in this one.
- **5.3 — Not horizontally scaled.** Real-time push uses an in-memory broker, so it reaches only
  clients connected to the instance that raised the notification. Running more than one instance needs
  a broker relay first — see `architecture.md`, "Known constraints".
- **5.4 — Not an open platform.** There is no public API, no API keys, no third-party integration, and
  the code is source-available rather than open source ([ADR-003](../ADRs/ADR-003-proprietary-source-available-licensing.md)).

---

## 6. Open questions

Undecided, and owned by the repository owner.

- **The daily check-in logs every active upgrade, including the ones left untouched.**
  `DailyCheckinPage`'s own documentation says an untouched upgrade is "left unlogged rather than
  recorded as missed", but `handleSubmit` posts an entry for each one. With BR-6 allowing a single
  entry per upgrade per day, an upgrade nobody filled in is therefore recorded as a failure that
  cannot be corrected until tomorrow. `DailyCheckinPage.test.tsx` asserts the behaviour and flags the
  disagreement; which of the two is right — submit-everything or submit-what-was-touched — is a
  product decision, and FR-19 does not settle it
  ([#55](https://github.com/NaimElijah/UpHealther/issues/55)). The page makes the conflict likelier than
  it needs to be: it never loads today's entries, so it offers an upgrade already logged elsewhere, and
  it posts every entry at once, so one refusal can leave the rest saved with nothing shown.
- **Whether reminders should fire for an upgrade that is not active.** They fire today whatever the
  upgrade's status, paused, completed and abandoned included, and nothing states whether they should
  ([#102](https://github.com/NaimElijah/UpHealther/issues/102)).
- **Whether an upgrade overdue a second time is announced again.** BR-11 announces an overdue upgrade
  once, and that once is permanent: an upgrade whose target date is moved later and missed again gets
  no second notice ([#103](https://github.com/NaimElijah/UpHealther/issues/103)).
- **Whether the list filters should combine.** FR-11's filters are alternatives — the API applies the
  first one it is given and ignores the rest, deliberately. If the interface grows filters, combining
  them may be what a user expects ([#90](https://github.com/NaimElijah/UpHealther/issues/90)).
- **Whether a failed entry today breaks a streak today.** BR-9 says a day not yet logged does not
  break one, and `StreakCalculatorTest` pins that. The calculator also treats a day logged as *not*
  completed the same way until the day is over, which no test asserts and no requirement states.
- **There is no accessibility target beyond dialogs and contrast.** FR-40 and NFR-19 are the only
  accessibility requirements, so the keyboard, labelling and announcement gaps the audit found are not
  defects against anything. Whether to adopt a target such as WCAG 2.2 AA is undecided
  ([#104](https://github.com/NaimElijah/UpHealther/issues/104)).

- **The frontend has no accessibility or visual-regression gate.** Contrast ratios in the theme were
  computed by hand; nothing re-checks them when a colour changes, and jsdom cannot — it has no layout
  engine. Closing this needs a real browser in CI; [ADR-004](../ADRs/ADR-004-frontend-test-harness.md)
  records why that was deferred. FR-39 and NFR-20 land in exactly this gap: no test in the suite can
  observe a width, a wrap or an overflow, so both were verified by hand and neither is enforced
  ([#61](https://github.com/NaimElijah/UpHealther/issues/61)).
- **Nothing verifies that a browser honours the dialog's `inert`.** The focus trap and the inert page
  behind it are built and tested ([ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md)),
  but jsdom implements `inert` not at all, so the tests pin that the attribute is set and cleared on
  the right nodes and nothing further. This is the same gap as the entry above and closes with it.
  Two smaller residuals go with it: focus falls back to `<body>` when the element that opened a dialog
  unmounted along with it, which the health-areas delete path does; and a toast raised while a dialog
  is open goes inert with the rest of the page, so it is painted above the dialog and cannot be
  dismissed ([#74](https://github.com/NaimElijah/UpHealther/issues/74)).
- **The backend has no dependency vulnerability audit.** OWASP dependency-check needs an `NVD_API_KEY`
  secret; ADR-002 records why a check that cannot fail was judged worse than none, and
  [#59](https://github.com/NaimElijah/UpHealther/issues/59) that it is no longer the only tool.
- **Nothing enforces that a rolled-back write raises no notification.** The notification listeners
  wait for the commit, and `architecture.md` describes the guarantee, but no test would notice a
  listener that did not. NFR-49 holds only the half that is tested: the live push waits for the commit.
- **The frontend's copies of the API's shapes are unchecked.** NFR-10 keeps the mirrored enums in step
  with the backend; the request and response types are written by hand, and nothing notices when one
  drifts (`architecture.md`, "The frontend's types are hand-written, not generated").
- **How long the audit trail must be kept is not stated.** The trail is a log stream
  ([ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md)), so it lasts as long as `docker logs` keeps a
  line. That is enough for diagnosis; whether it has to be more is a requirement nobody has set.
- **No ceiling on the free-text fields.** `description`, `motivation`, `successCriteria`, `note`,
  `whatWorked`, `whatDidNotWork` and `nextAdjustment` are `TEXT`, so BR-16 leaves them alone: nothing
  downstream rejects them and there is no 500 to prevent. What is left is that a single request can
  store as much prose as the servlet container will accept. How long a reflection may be is a product
  decision, not a defect, and nobody has taken it.
- **Whether `DataIntegrityViolationException` should be mapped at all.** BR-16 removes the known route
  to it, but it stays unmapped, so any constraint a DTO annotation cannot express still surfaces as a
  500. Mapping it centrally is not one decision but several — a unique violation, a foreign-key
  violation and a not-null violation do not deserve the same status, and `DuplicateProgressException`
  already shadows the first of them. The one other unique violation with a known route, a registration
  losing a race for an address, is translated where it happens (the user persistence adapter) and
  answered like any duplicate; the central question stands for everything else.
- **`UpgradeType.PROTOCOL` is deprecated but retained** for rows that may already carry it. Removing it
  needs confirmation that no stored row uses it.
- **No governing jurisdiction is named in the licence** — ADR-003 flags this as the first thing to add
  if the project ever becomes commercially significant.
- **Requirement priorities and delivery order are not recorded here.** Everything above is already
  built, so nothing has needed ranking yet. Planned work lives on the project board, starting from
  [#16](https://github.com/NaimElijah/UpHealther/issues/16).

---

UpHealther is still evolving and expanding, and so are its requirements — this document records what
the project meets today, not the limit of what it will do.
