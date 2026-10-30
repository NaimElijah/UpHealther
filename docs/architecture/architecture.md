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

Inside the API, the code is organised as ten bounded contexts over a ports-and-adapters core. The
core is not free of frameworks, and says where it is not. The domain imports only the JDK packages it
uses, Lombok and this codebase, with one exception: the entities carry `jakarta.persistence` mapping
annotations ([ADR-001](../ADRs/ADR-001-ddd-hexagonal-architecture.md)). The application layer imports
no adapter, no web type and not Spring's event bus, but it uses Spring where that pays, for example:
its services are Spring beans with `@Transactional`; `AuthService` takes Spring Security's
`PasswordEncoder` and `AuthenticationManager`; `NotificationService` registers a transaction
synchronisation; the sweeps recognise `CannotCreateTransactionException`; and the `*BeansConfig` and
properties classes are `@Configuration` and `@ConfigurationProperties`. That list is illustrative;
ArchUnit says what is forbidden. HTTP, Spring Data, token signing and the event bus reach the core
through adapters. The boundaries are not a convention — they are checked on every build by an
ArchUnit suite, which is the authority on what the layering permits.

> **Diagram:** [System context](arch-diagrams/README.md#1-system-context) — the three
> processes, the browser, and what each connection carries.

---

## Components

### Runtime processes

| Component | What it is | Responsible for |
|---|---|---|
| **Frontend** | nginx serving a Vite production build (container port 80, published on `127.0.0.1:3000`) | Serving the SPA, and proxying `/api` and `/ws` to the backend so the browser stays same-origin |
| **Backend** | Spring Boot 4.1 on Java 21, executable jar (port 8080, published on `127.0.0.1` by compose) | The whole domain: authentication, the upgrade lifecycle, tracking, reflections, reminders, the dashboard read model, notifications, and the scheduled jobs |
| **Database** | PostgreSQL 15 (port 5432, published on `127.0.0.1` by compose; volume `postgres_data`) | Every piece of persistent state. Schema owned by Flyway. Under compose the backend connects as a role that owns the database and is no superuser, created by `scripts/postgres-init/` on an empty volume ([ADR-069](../ADRs/ADR-069-the-compose-backend-connects-as-a-role-that-owns-its-database.md)) |

In development the nginx container is not involved: the Vite dev server serves the SPA on 3000 and
performs the same `/api` and `/ws` proxying itself, so the app behaves identically on both sides.

The production build also writes `THIRD-PARTY-NOTICES.txt` into `dist/`: Vite's `build.license` lists
every package in the JS bundle, and a small plugin in `frontend/vite.config.ts` adds Tailwind CSS. The
image copies `dist/` whole, so nginx serves the file at `/THIRD-PARTY-NOTICES.txt`
([ADR-059](../ADRs/ADR-059-the-bundle-ships-its-third-party-notices.md)).

### Backend bounded contexts

Ten contexts, each owning its own vocabulary, plus a cross-cutting `common`.

| Context | Owns | Shape |
|---|---|---|
| `upgrade` | `HealthUpgrade` — the core aggregate and its state machine | Full: domain, application, adapters |
| `tracking` | `TrackingConfig`, `ProgressEntry`, streak and success evaluation | Full |
| `reflection` | `Reflection` — periodic written reviews | Full |
| `reminder` | `Reminder` and its day-of-week schedule | Full |
| `notification` | `Notification` — the inbox and the real-time push | Full, and the only context with a messaging adapter |
| `healtharea` | `HealthArea` — the groupings upgrades are filed under | Full |
| `user` | `User` — identity and the stored password hash; `EmailAddress`, the one rule for when two addresses are the same identity | Full |
| `auth` | `AuthSession` — a signed-in session, the row that makes revocation possible — and the use cases around it: registration, sign-in, sign-out, and announcing a session's end so the sockets it opened close | Full, and it orchestrates over `user` for identity |
| `admin` | Listing accounts, switching one off or back on, and granting or revoking a role | Two-layer: orchestrates over `user` and `auth`, owns no aggregate. It depends on no context holding a user's own records, and ArchUnit keeps it that way |
| `dashboard` | The composed dashboard read model | Two-layer: reads through other contexts' ports, persists nothing |
| `common` | The `DomainEvent` marker and the `DomainEventPublisher` port; the shared exceptions, including `ConflictException`, which a context subclasses to name its own conflict; the audit trail (`AuditTrail` and `LoggingAuditTrail`); the ports it declares for a context to supply or call, `SessionStatusPort`, `AccountCredentialsPort` and `LiveSocketPort`; the global exception handler; JWT security; the WebSocket configuration and the registry of open sockets; and `observability`, `ratelimit` and `time` | Not a context; a shared kernel plus cross-cutting adapters. It depends on no context, and ArchUnit keeps it that way ([ADR-062](../ADRs/ADR-062-the-shared-kernel-depends-on-no-bounded-context.md)) |

The shapes that differ are deliberate, not drift. `dashboard` is two-layer by design
([ADR-002](../ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)); `auth` began
that way and gained an aggregate with sessions
([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)), so it is now full;
and `admin` owns no aggregate at all, orchestrating over `user` and `auth`.

A context keeps its adapters under `adapter/in` (what starts work) and `adapter/out` (what work reaches
out to). The kinds that occur:

| Package | Holds | Found in |
|---|---|---|
| `adapter/in/web` | Controllers, request and response records, and the `*WebMapper` that translates in both directions (`auth` and `admin` translate in the controller) | every context but `user` |
| `adapter/in/scheduling` | A cron trigger: times the run and calls the application method that holds the sweep, reading the clock first where the sweep needs a date (the session clean-up does not: its service reads it) | `upgrade`, `notification`, `auth` |
| `adapter/in/event` | A listener that turns a domain event into a call on the application layer, or, for `SessionEndSocketListener`, into the one `LiveSocketPort` call that closes a session's sockets: the driving-adapter rule's named exception | `notification`, `auth` |
| `adapter/in/composition` | The adapter that answers another context's or the kernel's outbound port: `UpgradeTrackingSummaryAdapter` for `UpgradeTrackingSummaryPort`, `SessionStatusAdapter` for `SessionStatusPort`, `AccountCredentialsAdapter` for `AccountCredentialsPort`. It sits under `adapter/in` because it calls its own context's inbound port, and implements a port rather than starting work | `tracking`, `auth`, `user` |
| `adapter/in/bootstrap` | `AdminBootstrapRunner`, an `ApplicationRunner`: at startup it parses `app.admin.bootstrap-user-id` and calls `AdminBootstrapService.bootstrap`, which nothing else may hold | `admin` |
| `adapter/out/persistence` | A package-private Spring Data repository and the `*RepositoryAdapter` that implements the context's repository port over it | every context but `admin` and `dashboard` |
| `adapter/out/messaging` | The STOMP push of a notification | `notification` |
| `adapter/out/security` | Access-token issuing over `JwtTokenProvider`, and the refresh secret generator | `auth` |

`common/adapter` holds the kernel's own: the global exception handler (`in/web`), `LoggingAuditTrail`
(`out/audit`) and `SpringDomainEventPublisher` (`out/event`). The rest of the kernel's adapters live
in packages not named `adapter` (`security`, `websocket`, `observability`, `ratelimit`). Only one rule
sees them as adapters: `core_does_not_depend_on_the_kernels_adapters`, which lets the application and
domain layers reach the kernel in `common.domain` and nowhere else ([ADR-064](../ADRs/ADR-064-the-backends-layering-rules-beyond-adr-002.md)).

### Frontend modules

| Module | Responsible for |
|---|---|
| `src/api/` | One axios instance, `client.ts`, and one module per backend area with a thin function per endpoint. The instance attaches the in-memory access token, and renews it from the refresh cookie and retries once on a 401 rather than signing the user out; `tokenStore.ts` holds the token, and every HTTP call goes through the instance. `api/` owns the credential's renewal and the end of a session: `session.ts`'s `endSession` is the one way a session ends, on sign-out, when a renewal fails and on the server's session-ended frame, by dropping the token, emptying what registered with its `onSessionEnd` (the query cache, and the application `main.tsx` mounted), and replacing the document with `/login`, never a router push, so no state of the previous user survives. A page the browser restores from its back/forward cache reloads, because its script state is the one it was left with ([ADR-066](../ADRs/ADR-066-a-tab-keeps-nothing-of-a-session-that-ended.md)). An id goes into a path only through `apiPath.ts`'s template tag, which encodes it as one segment and refuses an empty, `.` or `..` one, because a route's `:id` arrives decoded. `apiError.ts`'s `toApiError` is the one place a failure is decoded, into a status, a message, any field errors and the trace id. `notificationSocket.ts` is the STOMP client that delivers notifications live: it presents and renews the same token, and takes the API's origin from `apiBaseUrl.ts`, as the instance does |
| `src/contexts/` | `AuthProvider` owns the current user and the sign-in, registration and sign-out actions. It writes the token three times: it stores the one a sign-in or a registration returns, and clears it when the restore at page load fails; a sign-out the server accepted ends through `endSession`; `NotificationProvider` owns the notification list, the unread count (the server's, since the list holds only the fifty most recent; the unread listed stands in until it can be read), when the STOMP connection is open, and the toasts; `ThemeProvider` owns the light/dark/system choice and the `dark` class on `<html>` |
| `src/hooks/` | `useAuth`, `useNotifications` and `useTheme` — typed context readers that fail loudly outside their provider |
| `src/App.tsx`, `src/main.tsx` | The outer half of the composition root. `main.tsx` mounts `App` under `StrictMode`, and unmounts it when a session ends, through `onSessionEnd`; `App` installs, outermost first, `ThemeProvider`, `ErrorBoundary`, the one `QueryClientProvider` and `AuthProvider` around the router |
| `src/router/` | `index.tsx`, the route table and the inner half of the composition root: inside `BrowserRouter` it mounts `NotificationProvider` around the routes and `ToastContainer` beside them, so a toast can navigate. `ProtectedRoute` gates every page but sign-in and registration; `RequireRole` adds the administrator check in front of `/admin/users`, a courtesy, since the API decides. `/` and any unknown path redirect |
| `src/pages/` | One component per route that renders a page |
| `src/components/` | `ui/` primitives — including `PageContainer`, which decides how wide a page may grow, `Modal`, which every dialog is, and `ErrorState`, which shows a failure with its trace id — `upgrade/` cards, badges, the fields that describe an upgrade, the actions an upgrade's page offers on it, and the sections of that page (its streak, tracking configuration, reminders, progress and reflections), each rendering the read the page hands it and owning its writes and dialog where it has them, `notifications/` bell, dropdown, items and toasts, `layout/` navbar, sidebar and the phone menu, the last two rendering one shared list of links (`navItems.ts`), `theme/` the light, dark and system switch, and `ErrorBoundary.tsx`, which replaces a tree that threw while rendering with a page offering a reload |
| `src/types/` | `api.generated.ts`, the request and response shapes and enums rendered from the backend's records, and `index.ts`, which gives them the frontend's names and adds, as intersections, what no record declares: a health area's client-side `upgradeCount`, and the `upgradeId` that `createProgress` moves from a new entry into the URL (see "The frontend's wire types are generated from the backend's records") |
| `src/lib/` | Helpers shared by several pages. `localDate.ts` turns the API's zone-less `YYYY-MM-DD` dates into the user's own day and back, because JavaScript's shortcuts for both go through UTC; `queryClient.ts` builds the one server-state cache and its thirty-second staleness window, and registers it to be emptied when a session ends; `upgradeViews.ts` marks every cached view of upgrades out of date after a write that changed one ([ADR-029](../ADRs/ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md)) |

**The folders are layered, and lint enforces it.** Each imports only from those below it: `types` ←
`api` ← `lib` ← `contexts` + `hooks` ← `components` ← `pages` ← `router` ← `App.tsx` and `main.tsx`.
`contexts` and `hooks` are one layer, since each hook reads one context. A few modules have a single
owner: `axios` is imported only under `api/`, `@stomp/stompjs` only by `api/notificationSocket.ts`,
`types/api.generated` only by `types/index.ts`, and `api/tokenStore` only under `api/` and by
`contexts/AuthContext.tsx`. `eslint.config.js` enforces both with `no-restricted-imports`, on
production files only, so `npm run lint` fails an import that breaks either. See
[ADR-061](../ADRs/ADR-061-the-frontends-stack-and-layering.md), which also records why the SPA is built
on the libraries it is.

---

## Communication

Five distinct mechanisms, each used for one thing:

**1. HTTP, browser to API.** Every read and write. JSON in and out, JWT bearer token in the
`Authorization` header. The token names its account by id in `sub` and carries nothing else that
identifies it, and it is accepted only under this application's issuer, audience and signing
algorithm, so one minted by another service that happens to share the secret is refused. It must
carry an `exp`: JJWT checks one only when it is present, and a token without one would never lapse.
`BearerTokenAuthenticator` is the single place those rules live — the HTTP filter and the STOMP
interceptor both authenticate through it, so the two transports cannot drift apart. It accepts a
token only while the session its `sid` names is live and belongs to the account its `sub` names, so
a token pairing one account with another's session — which only a leaked key could mint —
authenticates nobody ([ADR-049](../ADRs/ADR-049-a-session-is-live-only-for-the-account-that-owns-it.md)).
It re-reads the account on every request, so the role it holds and whether it
is enabled are decided by the row as it stands and never by the token: revoking ADMIN, or switching
an account off, takes effect on the next request rather than whenever the token happens to lapse.
The HTTP filter, `JwtAuthenticationFilter`, runs only inside Spring Security's chain: its
registration as a servlet filter of its own is switched off, because a copy running ahead of the
chain would have its authentication discarded and leave every request anonymous (#238). Same-origin
through the proxy, so no CORS preflight in the default setup; the `CORS_ALLOWED_ORIGINS` policy
exists only for deployments that split the origins.

The two endpoints reachable without a credential — sign-in and registration — are rate-limited per
client address by a handler interceptor, which runs before any controller so a refused attempt costs
no password comparison. Refresh and sign-out, which act on the cookie alone, have an interceptor and a
limiter of their own, with a looser limit and a separate count, so a refused attempt reads no session
and writes no audit entry, and neither allowance spends the other. The address both count is the one
nginx reported, and only nginx's own address is trusted to report one. nginx sets the four
`X-Forwarded-*` headers Tomcat reads, `For`, `Proto`, `Host` and `Port`, so none reaches Tomcat as the
caller wrote it. The host follows the request's `Host` header, because the server block names no
`server_name` until [#209](https://github.com/NaimElijah/UpHealther/issues/209)
([ADR-017](../ADRs/ADR-017-an-in-process-fixed-window-rate-limit-per-client-address.md)).

**2. STOMP over WebSocket, API to browser.** One-way in practice: the browser connects and subscribes,
the API pushes. The handshake itself is unauthenticated — a browser cannot set headers on it — so the
JWT travels in the STOMP `CONNECT` frame and is validated by a channel interceptor, which attaches a
principal named by user id. Messages are routed to `/user/queue/notifications`, which the broker
resolves per session using that principal. Every CONNECT that presents a credential is audited as
`auth.connect`, allowed or refused (NFR-23), because the socket stays signed in as that user for as long
as it is open; a refusal names nobody, as a refused login does. A CONNECT with no `Authorization` header
— what a tab whose session could not be renewed sends every five seconds — presented nothing and is not
an entry, as a refresh with no cookie is not. A refusal reaches the browser as a STOMP ERROR frame
whose message is Spring's generic one, the same whatever the cause, an outage included, so the client
keeps its token through one and retries. Spring logs some refusals at ERROR, so nginx throttles the
`/ws` handshake per client address: sixty a minute with a burst of twenty, over the limit answered 429.
It bounds handshakes, not what one open socket sends, which
[#382](https://github.com/NaimElijah/UpHealther/issues/382) tracks, and it caps no open sockets
([ADR-070](../ADRs/ADR-070-nginx-throttles-the-websocket-handshake-per-client-address.md)).

The same interceptor authorises the frames that follow, because a session that is merely connected
can still name any destination it likes. A SUBSCRIBE must come from an authenticated session and
name `/user/queue/notifications` exactly — the resolved `/queue/notifications-user…` of another
session, and any `/topic`, are refused — and a SEND is refused outright, the application declaring
no `@MessageMapping` for one to reach. A socket holds one subscription for its life, kept in its
session attributes: a second SUBSCRIBE is refused, because each subscription is registered with the
broker and a socket streaming them would grow the heap without limit. An UNSUBSCRIBE frees nothing,
because the inbound channel's pool runs frames in no fixed order, and one that reached the broker
before its SUBSCRIBE would leave that subscription held. Heartbeats, UNSUBSCRIBE and DISCONNECT pass:
they name nothing, and refusing a DISCONNECT would leave sessions to time out rather than close. A
refused SUBSCRIBE or SEND is a WARN line, not an audit entry: it authorises a frame on a connection that
is already authenticated. Spring
adds an ERROR line of its own for a refused SEND, and for a frame it cannot decode, which no interceptor
can suppress; a refused CONNECT or SUBSCRIBE it logs only at DEBUG.

A connected socket sends nothing the interceptor could check again, so the server closes it instead
when its session ends ([ADR-054](../ADRs/ADR-054-a-session-that-ends-closes-its-open-sockets.md)).
The principal the CONNECT attaches also keeps the session its token was issued within, and
`StompSessionRegistry` in `common.websocket` follows Spring's connect and disconnect events to know
which socket belongs to which session and account. It asks `SessionStatusPort` once more as it records
a socket, because a session can end after the CONNECT's own check and before the socket is recorded,
which no sweep could see; a socket whose session has ended, or cannot be confirmed, is closed at once.
When `auth` ends one session — a sign-out, or a
replayed refresh credential, presented to refresh or to sign-out — it publishes `AuthSessionEnded`; when it ends all of an account's, as
switching the account off does, or a refresh that finds the account already switched off, it publishes
`UserSessionsEnded`. `SessionEndSocketListener` takes
both after the commit and calls `LiveSocketPort`, which the registry implements: a port the kernel
declares, as it does `SessionStatusPort` (mechanism 4), so that `auth` and `common` meet at an
interface. The registry sends each matching socket
a STOMP ERROR frame (`message: Session ended`), after which Spring closes the WebSocket. Sign-out is
per device, so only the sockets opened in that session close. That frame, and no other ERROR, ends
the session in the client's tab through `endSession`: the token is dropped, the query cache emptied,
and the tab sent to `/login`, so a window whose session ended elsewhere stops showing its data at once
([ADR-066](../ADRs/ADR-066-a-tab-keeps-nothing-of-a-session-that-ended.md));
`SessionEndedFrameContractTest` holds the two copies of the message together.

The broker is Spring's in-memory simple broker. There is no external broker, so a push reaches only
clients connected to *this* instance — see "Known constraints" below.

**3. In-process domain events, context to context.** A context that changes state publishes a record
implementing `DomainEvent` through the `DomainEventPublisher` port. The adapter behind it delegates to
Spring's `ApplicationEventPublisher`, so consumers can bind to the transaction:
`@TransactionalEventListener(AFTER_COMMIT)` means a notification is never raised for a write that then
rolls back. Such a listener stores its notification in a transaction of its own (`REQUIRES_NEW`): the
committed transaction is still bound to the thread when the listener runs, and an insert that joined it
would never be committed ([ADR-028](../ADRs/ADR-028-a-raised-notification-is-stored-in-its-own-transaction-after-the-write-commits.md)).
Events raised outside a transaction — by the scheduled sweeps — use a plain `@EventListener`,
because there is no commit for a transactional listener to wait for. `auth` raises the two events that
end a session's sockets, and consumes them itself: `SessionEndSocketListener` writes nothing, so it
needs no transaction of its own, and it waits for the commit so that a socket's reconnect cannot read
a session whose end has not landed yet as live.

Events are asynchronous only in the sense of being decoupled; they are delivered in the same process,
and nothing is queued or persisted between publisher and consumer.

**4. Direct calls through ports, context to context.** One mechanism in two forms: a call through a
port the callee publishes, and a call through a port the caller declares.

*Through the callee's inbound port.* Where a context needs to *read* another, it calls that context's
`application/port/in` interface and receives domain objects, never a repository. `TrackingService`
confirms upgrade ownership this way before recording progress. What else may cross is a published
presentation model, which is not a port: `dashboard` embeds `upgrade`'s `UpgradeDto` and maps it with
`UpgradeWebMapper`, so the dashboard's upgrades are the ones `/api/upgrades` returns. The slice rule of
`HexagonalArchitectureTest` lets a context reach another's `adapter/in/web` only for a class named
`*Dto` or `*WebMapper`, never for a controller or a request record
([ADR-002](../ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)).

*Through a port the caller declares.* Where the arrow would point the wrong way, the side that needs
something declares an outbound port and the side that has it supplies the answer. `upgrade` declares
`UpgradeTrackingSummaryPort` and `tracking` answers it (see "Which contexts depend on which"). The
shared kernel never calls a context. Where `common` needs what a context knows, it declares an outbound
port in `common.domain.port.out` and the context supplies it: `auth` answers `SessionStatusPort`,
whether a session is live, and `user` answers `AccountCredentialsPort`, the account a token or a
sign-in names, each through a composition adapter
([ADR-062](../ADRs/ADR-062-the-shared-kernel-depends-on-no-bounded-context.md)). `LiveSocketPort` is the
kernel's third, and the other way round: `auth` calls it and the registry in `common.websocket`
implements it (mechanism 2).

**5. Scheduled invocation.** Four cron-driven jobs drive the core with no request behind them: the
overdue sweep, the daily check-in nudge, the per-minute reminder dispatch, and the nightly sweep of
sessions nothing can use again. Each scheduler is only the trigger: it times the run through
`JobMetrics`, reads the clock where the sweep needs a date, and calls the application method that
holds the sweep —
`UpgradeOverdueService.announceOverdue`, `NotificationSweepService.nudgeDailyCheckin` and
`dispatchDueReminders`, and `AuthSessionService.deleteUnusableSessions`. The sweeps read every user's
rows through sweep ports of their own — `UpgradeSweepQuery`, `ProgressSweepQuery` and
`ReminderSweepQuery` — which ArchUnit lets only the jobs' application services hold. The other query
ports of the contexts holding a user's own records take the owner's id, so a read a request can
reach returns another user's row as absent (BR-15), with one exception:
`TrackingConfigQuery.findByUpgradeIds`, which the upgrade responses use to show each upgrade's
tracking setup, returns configurations whoever owns them, because `tracking_configs` has no owner
column to filter on. Its one caller maps upgrades already loaded through user-scoped reads, so no
request-supplied id reaches it. ArchUnit pins every hop: every dependent of `TrackingConfigQuery` stays
inside `tracking`; `UpgradeTrackingSummaryPort`, through which the read reaches `upgrade`, serves only
`UpgradeWebMapper` and the adapter that implements it; and the mapper serves only `UpgradeController`
and `DashboardWebMapper`, the two classes that hand it upgrades. The read of every account is the other
unscoped read a request could reach, and it is on a port of its own, `AccountListQuery`, which only
`admin` may hold, not on the `UserQuery` every context reaches. Each unscoped read, the sweeps included,
is implemented by a package-private class of its own — `UpgradeSweeps`, `ProgressSweeps`,
`ReminderSweeps`, `TrackingConfigReads`, `AccountListing` — so no controller can inject the
implementation and go round the rule on its port
([ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md)).
The administrator bootstrap is the same shape: `AdminBootstrapRunner` parses its setting and calls
`AdminBootstrapService.bootstrap`, which applies the rules and records the promotion.
`inbound_adapters_do_not_drive_outbound_ports` keeps every class under `adapter/in` off the outbound
ports, under `domain` and `application` alike, with three named exceptions: a composition adapter, to
a port it implements and the records that port returns; `SessionEndSocketListener`, to `LiveSocketPort`;
and `UpgradeWebMapper`, to `UpgradeTrackingSummaryPort` ([ADR-064](../ADRs/ADR-064-the-backends-layering-rules-beyond-adr-002.md)).

### Which contexts depend on which

Derived from the imports, not from intent:

> **Diagram:** [Bounded-context map](arch-diagrams/README.md#2-bounded-context-map) —
> generated from the imports, so it is what the code does rather than what was intended.

`user` and `healtharea` depend on no other context. `upgrade` depends only on `healtharea`, through
`HealthAreaQuery.ownsArea`, so that an upgrade is filed only under an area the caller owns (BR-18).
The graph is acyclic, and ArchUnit fails the build if that stops being true.

The one edge that is not obvious is the absent one. An upgrade's response carries its tracking
configuration, which would mean `upgrade → tracking` — and `tracking → upgrade` already exists for
ownership checks. Instead `upgrade` declares `UpgradeTrackingSummaryPort` describing what it needs, in
a record it owns, and `tracking` supplies it through a composition adapter. Both arrows run the same
way. ADR-002 records why.

**The reader of that port is a web mapper, not a use case.** `UpgradeWebMapper` holds
`UpgradeTrackingSummaryPort` and, as it maps upgrades to `UpgradeDto`s, asks it for the configurations
of all of them in one call. No application service calls the port, so a use case returns a
`HealthUpgrade` without its tracking setup and the setup arrives later:

1. The controller calls the use case, which loads and saves the aggregate and returns it as its
   transaction ends.
2. The controller hands the aggregate to the mapper, which reads the configurations through
   `UpgradeTrackingSummaryAdapter` and `TrackingConfigQuery.findByUpgradeIds`. That read runs after the
   use case's transaction has returned, so the upgrade and its tracking setup in one response are two
   reads, not one snapshot.

`DashboardWebMapper` maps its embedded upgrades through `UpgradeWebMapper`, so the dashboard inherits
the read.

The indirection is accepted, and not moved into the use cases. Moving it would change the ten
`UpgradeService` methods the controller maps, and the dashboard's view, to return an upgrade together
with its setup: about a dozen signatures, to remove an indirection that is named here instead. The
cost is that a web mapper does I/O, which a reader of the layering rules would not expect.
`TrackingConfigQuery`, the port that answers, is not scoped by owner. ArchUnit keeps every dependent of
it inside `tracking`, every dependent of `UpgradeTrackingSummaryPort` but this mapper and its
implementer out, and every dependent of this mapper but its two callers out; mechanism 5, above, says
why that holds.

---

## Data flow

### A write, end to end

Recording a day's progress, which touches most of the machinery:

> **Diagram:** [Request lifecycle](arch-diagrams/README.md#6-request-lifecycle) —
> logging progress, from the browser through to the pushed notification.

Three things in that flow are easy to miss:

- **Ownership is checked by the query, not by a guard.** Every repository lookup is scoped by user id,
  so a row belonging to someone else is indistinguishable from one that does not exist and surfaces as
  404. That *is* the per-resource authorization layer; there is no second one. An account's role does
  not change it — `ADMIN` opens the `/api/admin/**` paths and nothing else, so an administrator
  reading another account's health data is not a rule that is enforced somewhere, it is a capability
  that does not exist ([ADR-016](../ADRs/ADR-016-roles-read-from-the-database-on-every-request.md)).
- **The server decides completion.** The `completed` flag the client sends is advisory; when the
  upgrade has a tracking configuration the entry is re-evaluated against the target, so streaks and
  rates cannot be inflated by a client.
- **The push happens after commit, and the notification is stored either way.** A browser that was
  offline sees it on its next fetch.

### A read, end to end

`GET /api/dashboard` is the widest read. `DashboardAggregationService` calls four inbound ports —
upgrades, progress entries, streaks, health areas — buckets the upgrades by status and date, computes
the weekly rate, and returns a `DashboardView` of domain objects. The streaks of every active upgrade
come from one call, which reads all their histories in a single query. The web mapper then turns it into the
response, reusing the upgrade context's own mapper so the embedded upgrades are identical to what
`/api/upgrades` returns, and mapping each distinct upgrade once so a single batched query resolves
every tracking configuration rather than one per upgrade.

### A page's read, end to end

The browser's half of a read, and of the write that makes it stale:

1. **A page asks the cache.** It calls `useQuery` with a key and a function from `api/`, such as
   `['reminders', id]` and `getReminders`. The upgrade page starts its sections' reads as it mounts,
   beside the upgrade's own, and hands each section its result, since a section renders only once the
   upgrade has loaded. There is one cache, built by `lib/queryClient.ts` and provided once in
   `App.tsx`. An answer stays fresh for thirty seconds, so a page opened again inside that window
   renders from it without a request; a failed read is retried once.
2. **The call goes through the one axios instance.** `api/client.ts` adds the in-memory access token
   as a bearer header. A 401 is taken for a lapsed token: the instance renews it from the refresh
   cookie and sends the request once more. A renewal is single-flight, within the tab through one
   shared promise and across tabs through `navigator.locks`, because a rotated credential is good for
   one use. Only a renewal that fails ends the session, through `endSession`, whether the server
   refused it or did not answer within `REFRESH_TIMEOUT_MS`, ten seconds, after which it stops waiting,
   though the request itself runs on, so that the cross-tab lock is not held by a stalled one
   ([ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md)). A renewal
   answered 429, the session endpoints' per-address limit, is not a failure of the session: the
   request fails with that 429, the token is kept, and the next request tries again; at page load the
   tab shows nobody signed in and ends nothing, so a later load restores the session
   ([ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md)). The socket's own failed renewal
   does not end it either: it reconnects without a token, and the tab's next request ends the session
   ([ADR-054](../ADRs/ADR-054-a-session-that-ends-closes-its-open-sockets.md)).
3. **The request is same-origin.** Its base URL is relative unless `VITE_API_URL` is set, which is
   meant for an API on another origin (`api/apiBaseUrl.ts`). So `/api/...` reaches the backend through
   the Vite dev server's proxy, or through nginx in the image.
4. **The answer lands in the cache** under its key, typed by the generated wire types
   ([ADR-053](../ADRs/ADR-053-the-frontends-wire-types-are-generated-from-the-apis-records.md)), and
   every component reading that key renders it. A failure is decoded by `toApiError`, and a page that
   shows one renders it with `ErrorState`, trace id included. A few reads still show as empty when
   they fail ([#148](https://github.com/NaimElijah/UpHealther/issues/148)).
5. **A write marks views stale.** A mutation that changes an upgrade calls `refreshUpgradeViews`,
   which invalidates every key that shows upgrades, because one upgrade shows on several pages
   ([ADR-029](../ADRs/ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md)).
   Other writes invalidate the keys they name, such as a reminder's `['reminders', id]`. A progress
   entry names too few, and leaves the dashboard and the progress history stale for up to thirty
   seconds ([#192](https://github.com/NaimElijah/UpHealther/issues/192)). Invalidation refetches what
   a mounted page reads; the rest refetch when their page next opens.

**Notifications arrive by two routes into one cache.** While somebody is signed in,
`NotificationProvider` reads `['notifications']` over REST, and `api/notificationSocket.ts` hands it
each notification the server pushes over STOMP. A pushed one is written into the same list unless
its id is already there, so one that arrives both ways is listed once, and the unread count,
`['notifications', 'unread-count']`, is read again from the server. Marking read writes the cache
first and calls the API after; a refusal invalidates the list, so the server's answer replaces the
guess.

### A session, end to end

Two credentials with different lifetimes and different hiding places. The short one is in the body
and lives in the tab's memory; the long one is in a cookie the page cannot read, named
`__Host-refresh_token` with `Path=/` and no `Domain`, so that no other host can plant one. The server
keeps a row, which is what makes ending a session mean anything at all — see
[ADR-015](../ADRs/ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md).

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant C as AuthController
    participant A as AuthService
    participant S as AuthSessionService
    participant J as AccessTokenIssuerPort
    participant DB as auth_sessions

    Note over B,DB: Signing in
    B->>C: POST /api/auth/login
    C->>A: login(email, password)
    A->>S: open(userId)
    S->>DB: INSERT — only SHA-256(credential) is stored
    S-->>A: SessionGrant
    A->>J: issue(userId, sessionId)
    A-->>C: AuthResult
    C-->>B: 200 { token } · Set-Cookie: HttpOnly, Secure, SameSite=Strict

    Note over B,DB: Renewing, once the access token lapses
    B->>C: POST /api/auth/refresh — cookie + X-Requested-With
    C->>S: refresh(credential)
    S->>DB: SELECT … FOR UPDATE
    S->>DB: UPDATE — current := a new secret
    S->>DB: INSERT the spent digest, and when it was spent
    S-->>C: Rotated
    C-->>B: 200 { token } · a new Set-Cookie

    Note over B,DB: The spent credential comes back
    B->>C: POST /api/auth/refresh — the old cookie
    C->>S: refresh(spent credential)
    S->>DB: SELECT the spent digest — any rotation, not only the last
    alt inside its rotation grace window
        S-->>C: Stale — two tabs, or a retry · audited, refused
        C-->>B: 409 · try again, nothing revoked
    else after it
        S->>DB: UPDATE revoked := true
        S-->>C: Rejected · audited AUTH_TOKEN_REUSE
        C-->>B: 401 · cookie cleared
    end
```

Three things worth noticing. The row is read **`FOR UPDATE`**, so two tabs refreshing together
serialise instead of both rotating from the same starting state. Every digest a rotation retires is
kept in `auth_session_spent_tokens`, with when it was retired, for as long as the session lives and no
longer — its rows cascade with the session. That is the only reason a replay is distinguishable from a
guess, however many rotations old it is, and the grace window runs from the moment each one was spent.
Sign-out treats a spent credential the same way: inside its grace window it signs out, and after it the
session is revoked as reuse, through the one method refresh uses. A credential that is neither current
nor spent revokes nothing, because the session id travels in a token claim and is therefore not a
secret
([ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md)).
And the `sid` claim is checked on every authenticated request, together with the `sub` the row must
name as its owner, which is what turns a revoked row into a refused request rather than a wait for the
token to expire.

Every refresh and every sign-out the service is asked for is audited, as `auth.refresh` and
`auth.logout`, refused ones included (NFR-23). A refusal names the session whenever the row exists, and
its owner only when the presented credential is one the session issued — the session id is no secret,
so a wrong secret says nothing about who sent it. Naming the owner says whose credential it was, not who
sent it, which is also how the reuse entry names a credential that was probably stolen. A request with
no cookie at all has presented nothing; the controller answers it without asking the service, so the
refresh every signed-out page load makes is not an entry. Nor is a request with two of the cookie,
which the `__Host-` prefix should make impossible, since no other host can set one: `RefreshCookies`
still reads exactly one or none, answers the request as if it carried none, clears ours, and logs the
duplicate at WARN with the count and no value
([ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md)).

---

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
| `UpgradeOverdueScheduler` | daily 08:00 | Publishes `UpgradeOverdueDetected` for every active upgrade past its target date. The notification listener creates at most one notification per upgrade per target date it misses, so the repeated detection does not repeat the alert ([ADR-025](../ADRs/ADR-025-an-overdue-upgrade-is-announced-once-per-target-date.md)) |
| `NotificationScheduler.notifyDailyCheckin` | daily 18:00 | Nudges users who have active upgrades and have logged nothing today, at most once a day. Each of its two guards — who was already nudged since midnight, and who has logged today — is one query for the whole sweep. A nudge that fails to save does not cost the users after it |
| `NotificationScheduler.dispatchReminders` | every minute | Fires the reminders due this minute whose upgrade is active (BR-23). Due-ness is decided by the `Reminder` aggregate; the upgrades behind the due ones are loaded in one batch, and their status is read there, so a paused upgrade's reminders are silenced without being changed. A reminder that fails to save does not cost the ones after it |
| `AuthSessionCleanupScheduler` | daily 03:30 | Deletes revoked and expired sessions. Nothing depends on it running: an expired session is already refused by its own timestamps |

Each reads the clock through an injected `java.time.Clock`, which is what makes it testable without
waiting: the three notification and overdue jobs in the scheduler, and the session clean-up in
`AuthSessionService`, behind its scheduler. The clock and every cron keep time in UTC —
`ServerZone`, which each `@Scheduled` names
— so the defaults above are 08:00, 18:00, the minute and 03:30 in UTC, whatever the host's zone
([ADR-020](../ADRs/ADR-020-the-server-keeps-time-in-utc.md)).

---

## Observability

### Correlation

Every unit of work runs inside an observation, and the trace id it carries is what joins a failure
somebody reports to the log lines that produced it. There are three entry points and each gets its span
from a different place:

- **HTTP** — `ServerHttpObservationFilter`, from the framework. An inbound W3C `traceparent` continues
  the caller's trace; otherwise a new one starts. B3 is never read: `application.yml` sets
  `management.tracing.propagation.consume` to W3C, which Boot would otherwise extend to B3 and B3
  multi-header. Behind nginx no context arrives from a browser either: nginx clears the caller's, W3C
  (`traceparent`, `tracestate`, `baggage`) and B3 (`b3` and the `X-B3-*` headers) alike, on `/api` and
  `/ws`, so the id is the API's own
  ([ADR-067](../ADRs/ADR-067-trace-context-is-accepted-only-from-trusted-hops.md)).
  `TraceIdResponseHeaderFilter` returns the id as
  `X-Trace-Id`, and `GlobalExceptionHandler` stamps it on the error body. The container's error page
  runs after the scope has closed, so `TraceIdErrorAttributes` puts the id the header carried on its
  body instead ([ADR-024](../ADRs/ADR-024-the-container-error-page-keeps-its-status-and-carries-the-trace-id.md)).
- **Scheduled jobs** — `ScheduledMethodRunnable` already wraps each `@Scheduled` invocation in an
  observation; `ObservabilityConfig` supplies the registry that Boot leaves unset, which is the whole of
  it. The scheduler classes know nothing about tracing.
- **STOMP** — `StompTracingChannelInterceptor` on the inbound, broker and client-outbound channels. It
  spans two threads per frame, because a channel dispatches on the sending thread and handles on an
  executor thread; the sending span's context travels between them on a non-native message header.
  A frame's own trace headers are never read: a frame from the client starts a trace of its own, and
  one the server sends while serving a request joins that request's (ADR-067).

Nothing writes the MDC by hand. Correlation is a property of the runtime, so a new log statement,
controller or job is correlated without its author doing anything.
[ADR-007](../ADRs/ADR-007-request-correlation-through-micrometer-tracing.md) records why this is
Micrometer Tracing rather than a hand-rolled request id.

### Logging

`docker logs` is the only sink. There is no file appender, no log volume and no aggregator, so a line
that is not on stdout does not exist.

`logback-spring.xml` renders that stdout in one of two formats, chosen by profile: Boot's readable
console pattern by default, and one JSON object per line under `json-logs`, which `docker-compose`
sets. The plain-text branch imports Boot's `defaults.xml` rather than defining a pattern, because that
import is what carries `${LOG_CORRELATION_PATTERN}` — and therefore the trace id — into the output;
`LogOutputFormatTest` renders through the real encoder in both formats so that losing it fails a build.

Levels are load-bearing rather than decorative: **ERROR** is a fault a person must act on now, **WARN**
is degraded but still serving, **INFO** is a state transition, and **DEBUG** is for a developer reading
along. `com.healthupgrades` runs at INFO and is turned up for one run with `LOG_LEVEL_APP`. No log line
carries personal data — ids and enum values only, never a title, an email, a note or an IP — with one
exception: the ERROR line that reports an unexpected fault, a 5xx or a scheduled run that threw, carries
its stack trace and the exception's message, which can quote a stored row. Hibernate's SQL exception
logger, `org.hibernate.orm.jdbc.error`, is turned off in `application.yml`: it would otherwise write the
driver's message, row included, on every SQL failure, a handled refusal too.
[ADR-010](../ADRs/ADR-010-structured-logging-and-a-level-policy.md) records the format decision and the
policy, [ADR-026](../ADRs/ADR-026-a-scheduled-run-that-threw-is-logged-in-full.md) the exception, and
[ADR-027](../ADRs/ADR-027-hibernates-sql-exception-logger-is-turned-off.md) the silenced logger.

Three places are worth knowing about because they were silent and are no longer:

- **The scheduled jobs.** Each run is wrapped by `JobMetrics`, which times it and counts its outcome,
  and each writes one INFO line saying what it found and what it did. A sweep that has been throwing
  for a week used to look exactly like a sweep with nothing to do. The failure is rethrown rather than
  handled, because Spring's scheduler already logs a task that threw and two entries for one fault is
  worse than one. `dispatchReminders` stays silent when nothing is due — otherwise it writes a line a
  minute, all night, and buries the runs that did something.
  The two notification sweeps attempt each notification on its own. One that fails to save is a WARN
  naming it by ids and exception types, never by message, and the sweep moves on; a transaction that
  cannot begin is an outage, and the rest are skipped. The run's INFO line counts `failed` and `skipped`, and
  only then is the first failure rethrown, so the run is still counted `failed` and its stack trace
  is logged once ([ADR-023](../ADRs/ADR-023-a-notification-sweep-carries-on-past-a-failed-save-and-fails-the-run.md)).
- **The security boundary.** A rejected token is DEBUG with its exception type and never its message,
  which can quote the token back. A validly signed token naming an account that no longer exists is
  WARN: the signature was ours, so this is not ordinary expiry. Neither line names a subject. The
  token carries a user id and nothing else identifying, and that id is withheld on purpose: writing
  it down would tie the line to an account whose deletion is the thing the record was meant to end.
  A refused request, any 4xx, is DEBUG in the same way: its path, its status and its exception's type,
  because a validation failure's message quotes every rejected value, a password included.
- **A failed real-time push.** It runs from an `afterCommit` callback, so the notification is already
  durable; the failure is now reported at WARN, by ids and by its own and its root cause's exception types
with no stack trace, and the caller carries on, where before one unreachable
  session cancelled everybody else's reminders for that minute.

### In the browser

The SPA has no log sink and no telemetry, which `architecture.md` states elsewhere as a position rather
than an omission — so a browser-side failure can be *shown* and not *recorded*, and there is no
`console` call in any committed file.

What it can do is hand the user something to quote. `api/apiError.ts` decodes the backend's error
contract once, reading the trace id from the error body and falling back to the `X-Trace-Id` header —
every error body the backend builds carries the id, so the header covers a body that is not the API's
JSON at all. `ui/ErrorState` renders it: in place of a page that could not load, or inline beside the
control whose action failed — on the upgrade card for a status change, in the dialog for a delete,
beside the check-in's submit — from the mutation's own `error`. A refused mark-read is the notification
provider's `actionError`, since those writes are optimistic updates rather than mutations. A few reads
still render as empty when they fail ([#148](https://github.com/NaimElijah/UpHealther/issues/148)).
`ErrorBoundary`, mounted inside `ThemeProvider` and around the router, catches a render-time throw so
it becomes a themed, reloadable message instead of a blank page.

### Audit

`AuditTrail` is an outbound port in `common/domain/port/out/`, beside `DomainEventPublisher` and
cross-cutting for the same reason. Every state-changing use case records through it, as does every
authentication outcome — a sign-in, a refresh, a sign-out and a STOMP CONNECT, allowed or refused — and
the bootstrap promotion of the first administrator, and one read: an administrator's page of the account
list, the only read across accounts, which names the administrator and no resource; `LoggingAuditTrail` writes them to a logger named `AUDIT` at INFO, a level
`application.yml` pins so that turning the root level down cannot silence the trail, and derives the
`audit.events{action,outcome}` counter from the same call so the two cannot disagree.

An entry is `(action, actorUserId, resourceId, outcome)` — two enums and two identifiers, with **nowhere
to put free text**, which is how NFR-6 survives contact with twenty-one new call sites. One identifier is
not written as it is: when the resource is an `AUTH_SESSION`, the line carries the first sixteen hex
characters of the SHA-256 of the session id instead. The owner's id is on the same line, and an account
paired with a live session of it is the half of a forged token that a signing key alone lacks
([ADR-049](../ADRs/ADR-049-a-session-is-live-only-for-the-account-that-owns-it.md)). The digest is the
same for every entry about one session, so they still join up, and anyone holding a session id finds
its entries by computing it. Services record
through `AuditTrail.recording(...)`, which brackets the operation and records the refusal as well as the
success, or through `recordingCreation(...)` when the record the action names has no id until it is
saved — a new upgrade or area. A child record's creation, a reflection, a reminder, a progress entry
or a tracking configuration, is recorded against the upgrade it belongs to, and its action's `resource`
says `HEALTH_UPGRADE`, so the id on the line is always of the kind that field says, and a refusal still
names the upgrade that was probed. BR-15 answers an attempt on somebody else's record with a `404`, so
the trail is the only place that attempt exists at all
([ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md)). `REFUSED` (the system said no) and
`FAILED` (the system broke) are separate outcomes because they need separate reactions.

There is no audit table. An entry has to outlive the transaction it observes — a refused transition
rolls back, and a row written inside it would roll back too — and the trace id already on the line joins
the entry to its request without a foreign key.
[ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md) records the decision, what is deliberately not
audited, and what would reverse it;
[ADR-021](../ADRs/ADR-021-what-a-refused-authentication-names-and-what-is-no-attempt.md) records whom a
refused refresh, sign-out or CONNECT names, and which requests are no attempt at all.

### Metrics and health

`/actuator/prometheus` is the whole of the monitoring surface: request latency and error rate, JVM and
connection-pool saturation, `audit.events{action,outcome}` and `scheduled.job.runs{job,outcome}` with
its duration timer. It is a pull endpoint, so it needs no collector to exist before it is useful — and
nothing scrapes it today, which is a deliberate stopping point rather than an omission.

**No metric tag may be unbounded.** An action, an outcome, a job name and a status are closed sets; a
user id, an upgrade title and a raw path are not.

The exposed endpoint set is stated by name — `health,info,prometheus` — because `/actuator/**` is
`permitAll`, so the exposure list is the only thing standing between a reader and `/actuator/env`.
`ActuatorEndpointsIT` asserts the negative: `env`, `heapdump`, `loggers`, `beans`, `mappings`,
`configprops` and `threaddump` answer `404`. It also pins the exposed set exactly: the discovery page
lists health, info and the scrape, and nothing else.

Liveness and readiness are separate, because an orchestrator acts on them differently — a failed
liveness probe means restart the process, a failed readiness probe means stop sending it traffic, and
restarting a process that cannot reach its database fixes nothing. The backend image's `HEALTHCHECK`
polls readiness; the frontend image's asks nginx for the page, because a static bundle has no
readiness of its own. The frontend waits for the backend to be *healthy* rather than merely started.
[ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md) records the decisions,
including why the management endpoints are not on a separate port.

## External dependencies and integration points

**There are no third-party APIs.** Nothing leaves the deployment: no payment provider, no email or
push service, no analytics, no AI service.

| Dependency | Used for | How it is reached |
|---|---|---|
| **PostgreSQL 15** | All persistent state | JDBC from the backend only. Credentials from `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`, which compose fills from `POSTGRES_APP_USER` and `POSTGRES_APP_PASSWORD` |
| **Flyway** | Schema ownership and migration on startup | Embedded in the backend; `V1`–`V9` apply in order at boot. An applied migration is never edited — Flyway checksums it, comments included — so a correction is another migration |
| **Browser WebSocket** | Real-time notification delivery | The `/ws` STOMP endpoint, proxied by nginx (or Vite in development). nginx throttles its handshake per client address, not what an open socket sends (#382, [ADR-070](../ADRs/ADR-070-nginx-throttles-the-websocket-handshake-per-client-address.md)) |
| **nginx** | Serves the built SPA and proxies `/api` and `/ws` | Also where the security headers live — the content security policy, framing and referrer rules, declared for the whole server so that nginx's own error pages under `/api` and `/ws` carry them too ([ADR-018](../ADRs/ADR-018-a-content-security-policy-with-a-hashed-inline-boot-script.md)). Absent from `npm run dev`, which serves no headers |
| **Browser Notification API** | Desktop notifications when the tab is backgrounded | Optional, permission-gated, and skipped entirely where the API is unavailable. Each carries the notification's title and message, which the operating system keeps after the session ends ([ADR-068](../ADRs/ADR-068-desktop-notifications-keep-their-detail.md)) |

Integration points a maintainer will need:

- **`/actuator/health`, `/actuator/health/{liveness,readiness}`, `/actuator/info`,
  `/actuator/prometheus`** — the backend's operational surface, unauthenticated and closed to these by
  name. The compose and image health-checks poll **readiness**. `/actuator/info` answers the `build` the
  jar was made from — its group, artifact, name and version — which the `build-info` goal of
  `spring-boot-maven-plugin` writes into it
  ([ADR-050](../ADRs/ADR-050-one-version-names-the-whole-repository.md)). Nothing else under
  `/actuator` answers.
- **Environment variables** — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` are required in any
  real deployment, and without `JWT_SECRET` the application refuses to start: the jar carries no
  signing key. A run from `backend/` reads a published development key from
  `backend/config/application.yml`, which is never packaged, and compose passes the same value
  ([ADR-042](../ADRs/ADR-042-the-jar-carries-no-jwt-signing-key.md)). `CORS_ALLOWED_ORIGINS` and
  `VITE_API_URL` only matter when frontend and API are on different origins. The cron expressions are
  overridable per environment. `.env.example` lists them all.
- **CI** — GitHub Actions on every push and PR to `main` and `dev`. Each job runs its part of the gate
  through the root `package.json`, the same scripts `npm test` runs locally
  ([ADR-044](../ADRs/ADR-044-one-root-package-json-runs-setup-start-and-test.md)): the backend runs `./mvnw verify`,
  which compiles under javac's lint with `-Werror`, every category but `processing` and `serial`
  ([ADR-039](../ADRs/ADR-039-the-backend-compiles-under-javacs-lint-and-fails-on-a-warning.md)),
  whose integration tests start their own PostgreSQL through Testcontainers
  ([ADR-008](../ADRs/ADR-008-testcontainers-for-the-integration-test-database.md)), and which also
  checks the Java layout ([ADR-037](../ADRs/ADR-037-the-backend-is-formatted-by-palantir-java-format-through-spotless.md));
  then OSV-Scanner, from its own container with the sources read-only and no token, resolves the
  backend's whole dependency tree through deps.dev and Maven Central, checks it against api.osv.dev,
  and fails on any known vulnerability, or when any of the three cannot be reached ([ADR-047](../ADRs/ADR-047-the-backend-audits-its-dependencies-with-osv-scanner.md));
  the generated diagrams are checked against their source; the frontend checks its layout
  ([ADR-038](../ADRs/ADR-038-the-frontend-is-formatted-by-prettier.md)), lints, tests, audits its
  shipped dependencies and builds; and both images are built and the stack started from them until
  every service reports healthy, as its own compose project
  ([ADR-045](../ADRs/ADR-045-ci-builds-and-starts-both-images.md)), and then Playwright drives it in
  Chromium: a new account registers, plans and activates an upgrade, logs a day and reads the
  dashboard, and a second test fetches the third-party notices nginx serves, through the frontend's one
  port, published on `127.0.0.1:3000` only
  ([ADR-052](../ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md)).
  That suite lives in `e2e/`, a package of its own beside the two modules. The four jobs are required checks:
  a repository ruleset with no bypass refuses a merge into `main` or `dev` while any of them is red or
  pending ([ADR-043](../ADRs/ADR-043-ci-is-a-merge-gate.md)), and a second one refuses a merge into
  `dev` until they ran on the latest `dev` ([ADR-058](../ADRs/ADR-058-a-pr-into-dev-merges-only-once-its-checks-ran-on-the-latest-dev.md)).
- **Dependabot** — reads `.github/dependabot.yml` from `main` and opens its PRs against `dev`, where
  they pass the same four checks as any other. Once a week it groups each ecosystem's minor and patch
  updates into one PR: the backend's Maven dependencies, the frontend's npm dependencies, CI's actions,
  the Dockerfiles' base images and compose's images. The browser suite's Playwright, which moves the
  Chromium it drives, comes in a PR of its own. Each major gets a PR of its own. Updates that are a
  decision, which ADR-046, ADR-048 and ADR-060 list, are ignored and moved by hand: runtime, toolchain
  and database majors, React's, React Router's, Tailwind's and TypeScript's majors, and every Prettier
  release. `palantir-java-format`'s releases are moved by hand too, but need no ignore, because its pin
  sits where Dependabot reads no version ([ADR-037](../ADRs/ADR-037-the-backend-is-formatted-by-palantir-java-format-through-spotless.md)).
  GitHub also scans pushes for secrets and refuses one carrying a credential it recognises. It raises Dependabot alerts for every npm package, but for a Maven
  artifact only when `backend/pom.xml` states its version: its dependency graph does not resolve the
  pom, so nothing the Spring Boot parent manages and no transitive dependency is seen (#301). Those are
  repository settings, not files,
  and security-update PRs are off because GitHub would open them against `main`
  ([ADR-046](../ADRs/ADR-046-dependabot-proposes-weekly-updates-into-dev.md)).
- **Releases** — one version names both modules, and `RepositoryVersionContractTest` holds their four
  version fields together ([ADR-050](../ADRs/ADR-050-one-version-names-the-whole-repository.md)). A release is an
  annotated `v<version>` tag on its promotion merge on `main`, cut by hand. Its section of
  `CHANGELOG.md` is written beforehand by git-cliff, run from the pinned image in
  `scripts/compose.changelog.yml` as `npm run changelog`, and reaches `dev` with the version bump in
  a pull request of its own
  ([ADR-051](../ADRs/ADR-051-a-release-is-a-tag-on-its-promotion-and-git-cliff-writes-its-changelog.md)).
  Nothing runs when a tag is pushed.

---

## Structural decisions not visible from the file layout

**The dependency arrow always points inward.** `adapter → application → domain`, never the reverse.
No class in `application` imports anything from `adapter`, in either direction. Nor may a class in
`application` or `domain` reach the kernel outside `common.domain`, which keeps out the kernel's
adapters that live outside an `adapter` package — `common.security`, `common.websocket`,
`common.observability`, `common.ratelimit` — and any kernel package added later. So `AuthService`
issues tokens through `auth`'s `AccessTokenIssuerPort`, which `JwtAccessTokenIssuer` implements over
`JwtTokenProvider` ([ADR-064](../ADRs/ADR-064-the-backends-layering-rules-beyond-adr-002.md)). This
keeps HTTP, Spring Data, token signing and the event bus out of the core, and it is checked rather
than trusted. It does not make the core Spring-free: services carry `@Service` and `@Transactional`, and
use Spring elsewhere too, `AuthService`'s Spring Security types among them (see the overview). A service's
collaborators are its ports, though, so its unit test builds it with them mocked and starts no Spring
context; `AuthServiceTest` supplies the real BCrypt encoder and mocks the `AuthenticationManager`.

**The domain is framework-free, with one deliberate exception.** Domain classes import no Spring, no
Jackson, no servlet, no bean validation, and `domain_depends_only_on_what_it_needs` allows them only
the JDK packages they use (`java.lang`, `java.util`, `java.time`, `java.security`, `java.nio.charset`),
Lombok, this codebase's own types and `jakarta.persistence`, whose runtime types
`domain_uses_jpa_only_to_map` bans ([ADR-064](../ADRs/ADR-064-the-backends-layering-rules-beyond-adr-002.md)). They do carry JPA mapping annotations, because the
entities *are* the persistence model — there is no separate POJO-plus-mapper layer. All nine `@Entity`
classes sit in a `domain/model` package. That trade-off is ADR-001's, and ADR-002 reaffirms it.

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
Everything else depends on a repository port. That keeps the queries in one package per context, and
it does not make the store swappable by rewriting those packages alone. The entities in `domain/model`
carry the JPA mapping, `GlobalExceptionHandler` maps `jakarta.persistence.OptimisticLockException` to a
409, and the schema is PostgreSQL's, written in Flyway migrations whose foreign keys carry the
cross-context deletes (see the next paragraph).

**Deleting an upgrade reaches other contexts' tables through foreign keys, not through code.**
Every context's tables are in one PostgreSQL schema, and `tracking_configs`, `progress_entries`,
`reminders` and `reflections` reference `health_upgrades(id)` with `ON DELETE CASCADE`. That is how
FR-14 is carried out: `UpgradeService.delete` removes one row and publishes no event, and the import
graph shows no edge from `upgrade` to those contexts. `notifications.related_upgrade_id` and
`health_upgrades.area_id` use `ON DELETE SET NULL`, which is how a notification outlives its upgrade
(FR-14) and an upgrade survives its area (FR-8); `users(id)` cascades to every table that
carries a `user_id`. The entities map these columns as plain ids, so no JPA cascade exists to find. A
table that references `health_upgrades` or `users` states its `ON DELETE` outright, and moving a
context's tables out of the database, or giving one a soft delete, would replace the cascade
([ADR-063](../ADRs/ADR-063-one-schema-with-cross-context-foreign-keys.md)).

**Every request record and response DTO belongs to the web adapter.** Application services take command
records from `application/port/in` and return domain objects; a `*WebMapper` per context translates in
both directions. A wire-format change therefore cannot reach a use-case signature. `UpgradeTrackingConfigDto`
duplicating `TrackingConfigDto` field for field is the visible cost, and is intentional. One mapper
also reads: see "Which contexts depend on which".

**Optimistic locking is on one aggregate.** Only `HealthUpgrade` carries `@Version`. Two saves that
overlap inside the server clash, and the later one is answered 409. No request carries the version back,
though, so an edit sent from a page opened before another edit replaces that edit instead of being
refused ([#194](https://github.com/NaimElijah/UpHealther/issues/194)). Nothing else is version-checked,
because nothing else is edited from two places at once.

**Exception to HTTP status is decided in exactly one class.** Controllers and services throw domain
exceptions and never build a status by hand. The mapping is pinned by a test.

**No component names a colour.** Every colour in the SPA is a semantic token — `bg-surface`,
`text-fg-subtle`, `border-line-strong` — declared in `frontend/tailwind.config.js` and given its two
values, once per theme, in `frontend/src/index.css`. Components name the *role*; the theme decides the
value. `npm run check:colours` fails the build on any direct palette use, and is the only thing that
catches one, since Tailwind reports nothing for a class it does not recognise. Why the SPA styles this
way is in [ADR-061](../ADRs/ADR-061-the-frontends-stack-and-layering.md).

Two consequences a maintainer would otherwise have to rediscover. Tokens hold **space-separated RGB
channels**, not hex, because Tailwind's opacity modifier (`bg-overlay/50`) can only compose an alpha
onto a variable in that form. And the `dark` class on `<html>` is set **twice** — once by a classic
inline script in `index.html` that runs before the first paint, and thereafter by `ThemeProvider`. The
two must agree on the storage key and the resolution rule; changing one alone reintroduces the flash
the script exists to prevent.

**Nothing in the shell may be wider than the window.** `<main>` carries `min-w-0`, and that is
load-bearing rather than tidy. A flex item's `min-width` defaults to `auto`, which resolves to its
min-content width — so a single child that cannot shrink overrules `flex-shrink`, and the column, not
the child, is what grows past the viewport. `min-w-0` removes that floor and hands the pressure back to
the content, which is why every user-supplied string in the shell also carries `truncate` or
`break-words`, and why a health area's icon sits in a fixed clipping box. It replaced an
`overflow-auto` that was doing the same job invisibly: a flex item whose overflow is not `visible`
already has an automatic minimum size of zero. How wide a page may then grow is decided once, in
`PageContainer`, and not by the pages. See [ADR-005](../ADRs/ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md).

**A dialog is portalled to `<body>`, and it mutates the rest of the document while it is open.**
The overlay of `components/ui/Modal` is a direct child of `<body>` rather than of the page that
rendered it, so that "everything behind the dialog" is one list of nodes instead of an ancestor's
siblings at several depths — and every one of those nodes is given `inert` for as long as a dialog is
open, which is what makes its `aria-modal="true"` true rather than merely asserted. The marking is
refcounted in module state inside `Modal.tsx`, so two dialogs open at once behave, and the set of
nodes is snapshotted on the first open.

**The toasts are portalled to `<body>` too, and they are deliberately not behind a dialog.**
`components/notifications/ToastContainer` is exempt from the inert walk. A toast is a report about what just happened, often the very thing the dialog did, so it stays
live above an open dialog. `aria-modal` then holds for the page and not for the toast, which is
reachable by pointer only, because Tab stays inside the dialog. See
[ADR-019](../ADRs/ADR-019-a-toast-stays-live-above-an-open-dialog.md).

Two constraints fall out of the dialog's walk and bind anything added later. **A new `createPortal(...,
document.body)` has to declare itself**: it either spreads `aboveModalProps`
(`components/ui/aboveModal.ts`, the `data-above-modal` attribute), meaning it belongs above a dialog
and must not be inerted, or it is mounted before a dialog opens so the walk can see it — a portal that
appears while a dialog is already open is never marked and stays reachable behind one that says
nothing behind it is. And **`inert` is the mechanism, never `aria-hidden`**: Testing Library's role
queries treat an `aria-hidden` ancestor as non-existent, so using it would leave every future page
test that opens a dialog unable to query the page it is standing on. See
[ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md).

**The frontend's wire types are generated from the backend's records.**
`frontend/src/types/api.generated.ts` is written by a backend test, not by hand.
`FrontendWireTypes`, in the backend's test sources, finds every `@RestController` under `com.healthupgrades`,
then takes each handler's `@RequestBody` and return type. It renders every record they reach as an
`interface` and every enum as a union of its names. A component marked with JSpecify's `@Nullable`
admits `null`. A response declares every key, because Jackson writes nulls. A request may leave out
only the keys that admit null.

`FrontendWireTypesContractTest` fails `./mvnw test` when the committed file differs from that
render, and `npm run generate:wire-types` rewrites the file. `FrontendWireTypesJsonTest` holds the
render to the mapper the application runs with. It writes every response, filled in and with its
nullable components unset, and reads every request as its generated type spells it. So an enum's
`toString()`, an extra getter, a module or a global inclusion setting cannot make the API's JSON
differ from its generated type. `src/types/index.ts` gives the generated
types the frontend's names (`HealthUpgrade` for `UpgradeDto`, and so on), and adds by intersection
only what the API does not send. The same test fails if that file declares an interface, an object
type or an enum union of its own. So a wire change reaches the frontend as a `tsc` error at each use rather than as
a runtime surprise.

Calls are not covered: each `src/api/*.ts` function still pairs its method and path with its types
by hand (#328). The STOMP payload is covered only because it is `NotificationDto`, the REST
inbox's own type
([ADR-053](../ADRs/ADR-053-the-frontends-wire-types-are-generated-from-the-apis-records.md)).

**Request DTO bounds are hand-copied from the migrations, and checked the same way.** Every
`@Size(max = ...)` that mirrors a `VARCHAR(n)` exists because an unbounded field reaches the flush and
returns 500 rather than 400 (BR-16). Nothing structural connects the two: the entities declare no
`@Column(length)`, and Hibernate's `validate` checks a column's existence and type, not its width. So
`ColumnBoundContractTest` reads the migrations directly and fails the build when a bound and its
column disagree, or when a new bounded column is neither mirrored by a DTO nor listed as one no
request body writes to.

**One character is refused for every field at once.** PostgreSQL refuses U+0000 in any text column, so
a body carrying one reached the flush and returned 500 with an ERROR line. `NulRefusingStringModule`, a
Jackson module Boot registers with its mapper, refuses any string a request body carries that holds one,
which reaches the client as the 400 of a malformed body. It is one module rather than a constraint per
field so that a field added later is covered without anybody remembering it.

**The repository's commands are defined once, in the root `package.json`.** `npm run setup`,
`npm start` and `npm test` are its entry points, and CI's jobs call its `test:*` scripts rather than
spelling out their own, so a local run applies the checks CI applies. `setup` runs `setup:frontend`
and `setup:e2e`, and each CI job installs only the half it runs
([ADR-052](../ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md)). Only the inputs can differ: CI
installs the dependencies fresh, and sets none of the variables `docker-compose.yml` reads, which a
root `.env` or the shell may. It has no dependencies of its own. npm runs its scripts
through `cmd.exe` on Windows and `sh` elsewhere, so each body stays `cd dir && tool args`, and the two
commands no one string runs under both shells live in `scripts/`:
`mvnw.mjs` picks the Maven wrapper's spelling, and `python.mjs` finds a Python 3 under whichever name
the machine gives it ([ADR-044](../ADRs/ADR-044-one-root-package-json-runs-setup-start-and-test.md)).

### Known constraints

Stated because they are load-bearing, not because they are problems yet:

- **A single backend instance.** The STOMP broker is in-memory, so a notification pushed by one
  instance reaches only the clients connected to that instance. Notifications are persisted, so a
  client on another instance sees them on its next fetch rather than instantly. The registry that
  closes a session's sockets when it ends is in memory too, so a session ended on one instance could
  not close a socket held by another. Running more than one instance needs a real broker relay first,
  and a way to reach every instance's sockets with it.
- **`./mvnw test` needs no database; `./mvnw verify` needs Docker.** The integration tests boot the
  application against a real PostgreSQL that they start themselves through Testcontainers, so the
  database is described in the test source rather than supplied to it ([ADR-008](../ADRs/ADR-008-testcontainers-for-the-integration-test-database.md)).
  Keeping the unit suite database-free is a constraint worth preserving.
- **The container's error page runs outside the observation scope.** `ServerHttpObservationFilter`
  is registered for `REQUEST` and `ASYNC` dispatches but not `ERROR`, so a container error dispatch to
  `/error` has no span. Its body still carries the id the request's own dispatch saw, through a request
  attribute ([ADR-024](../ADRs/ADR-024-the-container-error-page-keeps-its-status-and-carries-the-trace-id.md)),
  but anything logged during it would have none; nothing logs there today. A request Tomcat itself
  refuses before any filter runs, such as an encoded `/` in the path, carries no id at all.
- **`docker logs` is the only sink, and its retention is the audit trail's retention.** There is no
  file appender, no log volume and no aggregator, so a line that has aged out of the container's log
  is gone — including the audit entries. That is adequate for diagnosis and is explicitly *not* a
  compliance story ([ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md)).
- **Nothing scrapes `/actuator/prometheus`, and no trace leaves the process.** The metrics endpoint is
  correct and unread, and sampling is at 1.0 with no exporter configured. Both are deliberate stopping
  points rather than omissions: the missing piece in each case is a deployment somebody is paged for
  ([ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md),
  [ADR-007](../ADRs/ADR-007-request-correlation-through-micrometer-tracing.md)).
- **A browser-side error can be shown but not recorded.** The SPA has no telemetry and no log sink, so
  `ErrorBoundary` can put a message on the screen and nothing else knows it happened. Adding a
  reporting endpoint is a decision about sending user data off the device, not a logging change.
- **A commit-time rollback is audited as a refusal, whatever caused it.** An `ALLOWED` entry is
  deferred to the commit, so work that rolls back is recorded — but `afterCompletion` does not say why,
  and an optimistic-lock clash, a lost unique-constraint race and an infrastructure failure at commit
  are indistinguishable there. All three are recorded `REFUSED`.
- **An outage that stops a transaction from starting is not audited at all.** Every use case records
  from inside its own `@Transactional` method, and with Hikari's default autocommit the connection is
  taken when the transaction begins — so a database that is down or a pool that is exhausted fails in
  the transaction proxy, before anything records the attempt. `AuthService.login`, which is not
  transactional itself, is the one exception. Tracked in
  [#133](https://github.com/NaimElijah/UpHealther/issues/133).
- **The browser and the server each have their own "today".** The SPA dates an entry by the browser's
  zone (`src/lib/localDate.ts`). The server reads "today" from its own clock, which keeps UTC
  ([ADR-020](../ADRs/ADR-020-the-server-keeps-time-in-utc.md)), and a user has no zone. That clock
  decides what counts as today's progress, the seven-day window and where a current streak starts. For
  as many hours as the two zones are apart, an entry the user made for their today can fall outside the
  server's today. East of the server just after midnight, the entry sits in the server's tomorrow, so
  the history and the streak leave it out until the server's day turns. A streak milestone is the
  exception: an entry dated after the server's today is judged from its own date, so its milestone is
  announced when it is made instead of being lost (`StreakCalculator.milestoneReachedBy`, BR-10). West
  of the server in the evening, the entry sits in the server's yesterday, so the dashboard counts the
  upgrade as not yet done today. No write is refused, and the entry is stored on the right day.
  Reminders keep the same clock: one set for 09:00 fires at 09:00 UTC wherever its owner is.
- **A refused login is a rate signal, not an attribution.** The audit entry deliberately names no
  subject, so the trail cannot say whose account was targeted and will not support a lockout policy as
  written. The same holds for a refused CONNECT, and for a refused refresh or sign-out whose credential
  the session never issued: that entry names the session, but not who presented the credential.

---

## Where the reasoning lives

| Question | Record |
|---|---|
| What does all of this look like? | [`arch-diagrams/`](arch-diagrams/README.md) — seven diagrams, outside in |
| What is the system supposed to do, and what is it deliberately not doing? | [`docs/requirements/requirements.md`](../requirements/requirements.md) |
| Why DDD + hexagonal at all, and why JPA entities as the domain model? | [ADR-001](../ADRs/ADR-001-ddd-hexagonal-architecture.md) |
| Why the `upgrade`/`tracking` dependency is inverted; why events moved out of `common`; what the ArchUnit rules cover; what was rejected and when to revisit | [ADR-002](../ADRs/ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) |
| Why the frontend tests with Vitest rather than Jest; why there is still no accessibility gate | [ADR-004](../ADRs/ADR-004-frontend-test-harness.md) |
| Why the frontend builds on Vite 8 and tests on Vitest 5; why the build's browser target is pinned to Vite 5's | [ADR-036](../ADRs/ADR-036-the-frontend-builds-on-vite-8-and-tests-on-vitest-5.md) |
| Why the integration tests start their own database instead of being handed one; why not H2 | [ADR-008](../ADRs/ADR-008-testcontainers-for-the-integration-test-database.md) |
| Which of the four test levels a new test belongs at, and why coverage is reported rather than gated | [ADR-009](../ADRs/ADR-009-test-levels-boundaries-and-naming.md) |
| Why correlation is Micrometer Tracing rather than a hand-rolled request id; why there is no exporter | [ADR-007](../ADRs/ADR-007-request-correlation-through-micrometer-tracing.md) |
| Why logs are JSON in a container but not locally; what each level means; why nothing personal may be logged | [ADR-010](../ADRs/ADR-010-structured-logging-and-a-level-policy.md) |
| Why Hibernate's SQL exception logger is off, and what a refused write still leaves behind | [ADR-027](../ADRs/ADR-027-hibernates-sql-exception-logger-is-turned-off.md) |
| Why the audit trail is a log stream rather than a table or Envers; why refusals are recorded; what is not audited | [ADR-011](../ADRs/ADR-011-audit-as-a-log-stream.md) |
| Whom a refused refresh, sign-out or CONNECT names; why a request with no credential, a per-request token check and a refused SUBSCRIBE are not audited | [ADR-021](../ADRs/ADR-021-what-a-refused-authentication-names-and-what-is-no-attempt.md) |
| Why metrics are a scrape endpoint and not an exporter or a Grafana stack; why the actuator surface is closed by name | [ADR-012](../ADRs/ADR-012-metrics-through-a-prometheus-scrape-endpoint.md) |
| Why every page shares one width; why the shell can be trusted not to overflow; why container queries were turned down | [ADR-005](../ADRs/ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md) |
| Why the dialog traps focus by hand rather than through a native `<dialog>`; why `inert` and not `aria-hidden`; why the overlay is portalled | [ADR-013](../ADRs/ADR-013-trapping-focus-without-a-native-dialog.md) |
| Why a phone gets the navigation in the shared dialog rather than a drawer or a tab bar; when the menu closes; why the brand leaves the navbar below 360px | [ADR-022](../ADRs/ADR-022-the-phone-navigation-is-the-shared-dialog-not-a-drawer.md) |
| Why a write to an upgrade refreshes every page's view of upgrades rather than its own, and why not a zero staleness window or patched caches | [ADR-029](../ADRs/ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md) |
| Why a notification sweep carries on past a failed save, still fails the run, and gives up only when the database cannot be reached | [ADR-023](../ADRs/ADR-023-a-notification-sweep-carries-on-past-a-failed-save-and-fails-the-run.md) |
| Why a lifecycle notification is stored in its own transaction after the write commits, and why a failed one can be lost but never fails the write | [ADR-028](../ADRs/ADR-028-a-raised-notification-is-stored-in-its-own-transaction-after-the-write-commits.md) |
| Why the container's error page is let through security by dispatcher type, and how it gets the trace id the request's own dispatch saw | [ADR-024](../ADRs/ADR-024-the-container-error-page-keeps-its-status-and-carries-the-trace-id.md) |
| Why an unauthenticated request is a 401 with the API's own body rather than the framework's 403 | [ADR-014](../ADRs/ADR-014-unauthenticated-requests-are-401-with-the-api-error-body.md) |
| Why one root `package.json` sets up, starts and tests the repository rather than `make` or `just`; why CI runs its scripts; why two small Node scripts | [ADR-044](../ADRs/ADR-044-one-root-package-json-runs-setup-start-and-test.md) |
| Why CI builds both images and starts the stack rather than two bare builds; why the check is its own compose project; why no image scan yet | [ADR-045](../ADRs/ADR-045-ci-builds-and-starts-both-images.md) |
| Why Dependabot opens weekly grouped updates against `dev`; why its security-update PRs are off; why some updates are ignored; why a `node` or `postgres` bump goes red until a person moves its companion | [ADR-046](../ADRs/ADR-046-dependabot-proposes-weekly-updates-into-dev.md) |
| Why the backend is audited by OSV-Scanner rather than dependency-check, Trivy or OSS Index; why any finding fails; why test scope is included; why Tomcat, Jackson and OpenTelemetry are overridden; why images are not scanned yet | [ADR-047](../ADRs/ADR-047-the-backend-audits-its-dependencies-with-osv-scanner.md) |
| Why Dependabot ignores React majors; why `react`, `react-dom` and their types move together, by hand | [ADR-048](../ADRs/ADR-048-dependabot-holds-react-majors-back.md) |
| Why one version names both modules rather than one each; why a test, not a CI step, holds the four fields together; why `dev` carries the last release's version | [ADR-050](../ADRs/ADR-050-one-version-names-the-whole-repository.md) |
| Why a release is a tag on its promotion merge, cut by hand; why git-cliff runs from an image rather than release-please, a script or an npm dependency; why the changelog is only ever prepended to | [ADR-051](../ADRs/ADR-051-a-release-is-a-tag-on-its-promotion-and-git-cliff-writes-its-changelog.md) |
| Why a browser drives the composed stack inside the image check, in Playwright's Chromium, rather than in a job of its own or only before a release; why `e2e/` is its own package; why the frontend publishes `127.0.0.1:3000` and the browser runs in UTC | [ADR-052](../ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md) |
| Why a session that ends closes its open sockets with an ERROR frame, after the commit; why not a check on every push or on the client's heartbeats; why a session's natural expiry is not covered. What the tab does with the frame is ADR-066's | [ADR-054](../ADRs/ADR-054-a-session-that-ends-closes-its-open-sockets.md) |
| Why the diagram check runs on any Python 3 rather than a pinned one; why it stays on the standard library | [ADR-055](../ADRs/ADR-055-python-is-left-floating-at-any-python-3.md) |
| Why the compose stack publishes on `127.0.0.1` only and probes PostgreSQL over TCP; why only the backend drops its capabilities | [ADR-057](../ADRs/ADR-057-the-developer-stack-is-reachable-from-this-machine-only.md) |
| Why a PR into `dev` must have its checks run on the latest `dev`, and a promotion into `main` need not; why a second ruleset rather than strict in the first | [ADR-058](../ADRs/ADR-058-a-pr-into-dev-merges-only-once-its-checks-ran-on-the-latest-dev.md) |
| Why the bundle ships a notices file for its third-party packages; why the jar and the images need none yet, and when that changes | [ADR-059](../ADRs/ADR-059-the-bundle-ships-its-third-party-notices.md) |
| Why Dependabot ignores the Tailwind CSS and TypeScript majors, and what moves each by hand | [ADR-060](../ADRs/ADR-060-dependabot-holds-the-tailwind-and-typescript-majors-back.md) |
| Why the SPA is built on TanStack Query, React Context, axios, `@stomp/stompjs`, a static build and Tailwind's semantic tokens; how its folders are layered, and why core ESLint enforces that rather than a plugin | [ADR-061](../ADRs/ADR-061-the-frontends-stack-and-layering.md) |
| Why the shared kernel depends on no context; why the security adapter loads accounts through a port it declares rather than through `UserQuery`; why `User` and `Role` stay in `user` | [ADR-062](../ADRs/ADR-062-the-shared-kernel-depends-on-no-bounded-context.md) |
| Why every context's tables share one schema; why a delete crosses contexts on `ON DELETE` rather than an event; what moving a context out or adding a soft delete would cost | [ADR-063](../ADRs/ADR-063-one-schema-with-cross-context-foreign-keys.md) |
| Which layering rules the backend enforces beyond ADR-002, and the exceptions each names; why `TrackingConfigQuery` stays unscoped and a web mapper reads a port; when to revisit either | [ADR-064](../ADRs/ADR-064-the-backends-layering-rules-beyond-adr-002.md) |
| Why a session remembers every refresh credential it spent, and why sign-out with a spent one is reuse; why a refresh re-reads the account; why the cookie is `__Host-` with `Path=/`, and two of them are refused; why refresh and sign-out have a limit of their own; the bounds on the lifetimes; why the audit stream names a session by a digest; how the unscoped reads are closed one hop further | [ADR-065](../ADRs/ADR-065-the-backend-hardened-after-scan-05.md) |
| Why a page the back/forward cache restores reloads, every time and not only after a sign-out; why a session's end also empties the query cache, through a registration `lib/` makes with `api/`; why the server's session-ended frame ends the session in the tab, superseding part of ADR-054 | [ADR-066](../ADRs/ADR-066-a-tab-keeps-nothing-of-a-session-that-ended.md) |
| Why trace context is continued only from a trusted hop: why nginx clears the caller's, why the API reads W3C and never B3, and why a STOMP frame never continues a trace; what an upstream tracer would need | [ADR-067](../ADRs/ADR-067-trace-context-is-accepted-only-from-trusted-hops.md) |
| Why a desktop notification carries the notification's own title and message, though the operating system keeps them after sign-out; when to revisit that | [ADR-068](../ADRs/ADR-068-desktop-notifications-keep-their-detail.md) |
| Why the compose backend connects as a role that owns its database rather than as the superuser; why its variables are not `DB_USERNAME` and `DB_PASSWORD`; why one role and not a migrating and a serving one | [ADR-069](../ADRs/ADR-069-the-compose-backend-connects-as-a-role-that-owns-its-database.md) |
| Why nginx throttles the `/ws` handshake although ADR-017 kept sign-in's limit in the application; why there is no cap on open sockets; what a load balancer would need first | [ADR-070](../ADRs/ADR-070-nginx-throttles-the-websocket-handshake-per-client-address.md) |
| Day-to-day conventions when changing backend code | [`backend/CLAUDE.md`](../../backend/CLAUDE.md) |
| Day-to-day conventions when changing frontend code | [`frontend/CLAUDE.md`](../../frontend/CLAUDE.md) |
| How to run, test and deploy it | [`README.md`](../../README.md) |
