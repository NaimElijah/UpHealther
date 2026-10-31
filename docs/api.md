# API reference

Every endpoint below `/api` except `POST /api/auth/register` and `POST /api/auth/login` requires a
bearer token: `Authorization: Bearer <token>`. Tokens last 24 hours and are not refreshable.

Worked request and response bodies are in the [README's Usage section](../README.md#-usage). This page
is the surface; [`requirements.md`](requirements/requirements.md) says what each capability must do,
and the `*ControllerTest` named against each area pins the status codes.

## Endpoints

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/register` · `POST /api/auth/login` · `GET /api/auth/me` |
| Health areas | `GET POST /api/health-areas` · `GET PUT DELETE /api/health-areas/{id}` — a `POST` answers `201` with `Location: /api/health-areas/{id}` |
| Upgrades | `GET POST /api/upgrades` · `GET PUT DELETE /api/upgrades/{id}` — a `POST` answers `201` with `Location: /api/upgrades/{id}` |
| Transitions | `POST /api/upgrades/{id}/…` — `plan` · `activate` · `pause` · `complete` · `abandon` · `reschedule` |
| Tracking config | `GET PUT /api/upgrades/{id}/tracking-config` — a `PUT` answers `201` when it creates the upgrade's configuration and `200` when it replaces one |
| Progress | `GET POST /api/upgrades/{id}/progress` · `GET /api/upgrades/{id}/streak` · `GET /api/progress/today` · `GET /api/progress/week` — a `POST` answers `201` without `Location`: an entry has no route that reads it back |
| Reflections | `GET POST /api/upgrades/{id}/reflections` — a `POST` answers `201` without `Location`: a reflection has no route that reads it back |
| Reminders | `GET POST /api/upgrades/{id}/reminders` · `PUT DELETE /api/reminders/{id}` — a `POST` answers `201` without `Location`: a reminder has no route that reads it back |
| Notifications | `GET /api/notifications` · `GET /api/notifications/unread-count` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all` |
| Dashboard | `GET /api/dashboard` |
| Sessions | `POST /api/auth/refresh` · `POST /api/auth/logout` — both act on the refresh cookie, `__Host-refresh_token` on `Path=/` (`HttpOnly`, `Secure`, `SameSite=Strict`, no `Domain`), and both require an `X-Requested-With` header. A request carrying two of the cookie is answered as one carrying none |
| Administration | `GET /api/admin/users` · `POST /api/admin/users/{id}/disable` · `POST /api/admin/users/{id}/enable` · `PUT /api/admin/users/{id}/role` — `ADMIN` only, answered 403 for anyone else. An administrator manages accounts and can read nothing an account owns. A disable, enable or role change overtaken by another write to the same account is answered 409, and can be resent after reloading ([ADR-073](ADRs/ADR-073-the-schema-refuses-what-the-domain-cannot-hold.md)) |
| Real-time | STOMP over `/ws`. The CONNECT frame carries `Authorization: Bearer <token>`; the client may subscribe to `/user/queue/notifications` and to nothing else, once for the socket's life (a second SUBSCRIBE is refused, after an UNSUBSCRIBE too), and a SEND is refused. A refused frame is answered with an ERROR frame and a closed socket, whose `message` is `Unauthenticated` for a CONNECT without a usable token, `Refused` for a SUBSCRIBE or SEND the server does not take, and `Frame not processed` for any other failure, an outage or a frame that could not be decoded. The handshake is throttled per client address by the proxy and answered 429 over the limit. When the session the token was issued within ends — signed out, revoked for a replayed credential, or its account switched off — the server sends the socket an ERROR frame with `message: Session ended` and closes it; the SPA then ends the session in that tab and goes to the sign-in page (ADR-066) |

`GET /api/upgrades` filters on `status`, `type`, `areaId` and `difficulty`.

An email is one identity however it is typed: the API trims and lowercases it before comparing or
storing it. `POST /api/auth/register` refuses an address already held with `422` and the message
`That email is already registered`, which does not repeat the address.

`POST /api/auth/register` refuses a password outside BR-17 with `400` and the reason under
`fieldErrors.password`: shorter than 8 characters, longer than 72 characters or 72 bytes of UTF-8, or
one of the 10,000 common passwords on the list the API bundles, compared in lower case after NFKC
normalisation. A common one is answered `is one of the most common passwords; choose one that is
harder to guess` ([ADR-071](ADRs/ADR-071-registration-refuses-the-most-common-passwords.md)).

A date-time in a body is an ISO-8601 local date-time with no offset, such as `2026-01-14T09:13:02`,
with a fraction of a second when it has one. A notification's `createdAt` is stamped from the server's
clock, which keeps UTC ([ADR-020](ADRs/ADR-020-the-server-keeps-time-in-utc.md)), and the SPA reads it
as UTC; so is the error body's `timestamp`. Every other `createdAt` and `updatedAt` is stamped by its
entity's persistence hook in the JVM's default zone, which is UTC in the container image but not on
every host ([#51](https://github.com/NaimElijah/UpHealther/issues/51)).

## A created upgrade, in full

`POST /api/upgrades` returns `201`, with `Location: /api/upgrades/{id}`. Nulls are serialised rather than omitted, so the body always
carries all eighteen fields — a client can rely on the key being present.

```bash
curl -sX POST http://localhost:8080/api/upgrades \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Walk 8k steps","type":"HABIT","difficulty":"MEDIUM","targetEndDate":"2026-12-01"}'
```

```json
{
  "id": "0f2c8f5a-2a4e-4a1d-8f0a-3c5b9d1e77a1",
  "userId": "8c1f6d20-90ab-4c33-9f14-2b7a5e0c1d88",
  "areaId": null,
  "title": "Walk 8k steps",
  "description": null,
  "type": "HABIT",
  "status": "IDEA",
  "difficulty": "MEDIUM",
  "plannedStartDate": null,
  "actualStartDate": null,
  "targetEndDate": "2026-12-01",
  "motivation": null,
  "successCriteria": null,
  "overdue": false,
  "version": 0,
  "trackingConfig": null,
  "createdAt": "2026-01-14T09:13:02",
  "updatedAt": "2026-01-14T09:13:02"
}
```

`status` is `IDEA` on creation and is absent from the request by design — it moves only through the
transition endpoints. `actualStartDate` stays null until the upgrade is activated, `overdue` is
derived at mapping time, and `version` is the optimistic-lock counter: echo a stale one back and the
write is a `409`. Field order is part of the contract in practice and is pinned by
`UpgradeDtoSerializationTest`.

## Status contract

Controllers never build a status by hand — they throw the domain exception that says what went wrong,
and `GlobalExceptionHandler` decides how it surfaces
([ADR-006](ADRs/ADR-006-framework-exceptions-through-responseentityexceptionhandler.md)).

| Status | Raised when |
|---|---|
| `400` | Failed validation, an unbindable body, or a parameter that will not convert. A fraction sent for an integer field, such as a rating of `3.7`, is an unbindable body: it is refused, never truncated ([ADR-033](ADRs/ADR-033-refuse-a-fractional-number-for-an-integer-field.md)). So is a body with U+0000 in any string, answered `Malformed request body` before it reaches a use case ([ADR-065](ADRs/ADR-065-the-backend-hardened-after-scan-05.md)) |
| `401` | Credentials rejected — the response never says which half was wrong — or a protected endpoint called without a token the server accepts, a token that names no expiry included. Both carry `WWW-Authenticate: Bearer`, with `error="invalid_token"` when a token was sent; expired, forged and revoked are not told apart |
| `403` | The caller is authenticated and not allowed |
| `404` | Not found, **including** a record belonging to another user: ownership is enforced by the query being user-scoped, so a foreign row is indistinguishable from a missing one |
| `409` | A duplicate progress entry for the same upgrade and date, a stale optimistic-lock `version`, an upgrade's first tracking configuration `PUT` by two requests at once, where the loser can be resent unchanged and replaces the winner's, an activation or promotion to `HARD` sent while another of the caller's is checking the concurrent-`HARD` limit, which changes nothing and can be resent unchanged ([ADR-074](ADRs/ADR-074-concurrent-writes-beyond-the-version-check.md)), or an administrator's disable, enable or role change overtaken by another write to the same account, which changes nothing and can be resent after reloading ([ADR-073](ADRs/ADR-073-the-schema-refuses-what-the-domain-cannot-hold.md)) |
| `422` | A business rule refused the operation — an illegal lifecycle transition, a fourth concurrent `HARD` upgrade, or an `areaId` that is not one of the caller's health areas (refused the same way whether it belongs to somebody else or does not exist) |
| `429` | Too many attempts from one client address in a minute: sign-in and registration together over ten, refresh and sign-out together over sixty, each counted apart. Carries `Retry-After`, in seconds, and is decided before any credential or session is read. The SPA keeps a session whose renewal is answered `429` |
| `405` `415` `406` | The status Spring defines for the framework exception |
| `500` | A genuine server fault. Carries the status and a trace id, and nothing else |

## Error body

Every failure returns the same shape, including a refusal decided inside the security chain.
`fieldErrors` appears only on validation failures and `traceId` only when the request was traced;
both are omitted otherwise. `timestamp` is UTC, written without an offset
([ADR-020](ADRs/ADR-020-the-server-keeps-time-in-utc.md)). One path does not reach the handler and returns Boot's default body
instead: a container error dispatch to `/error`, which renders a request the firewall refused and an
exception that escaped a filter. It answers with the status the container chose, and its body is
`timestamp`, `status`, `error` and `path`, plus the same `traceId` its `X-Trace-Id` header carries
([ADR-024](ADRs/ADR-024-the-container-error-page-keeps-its-status-and-carries-the-trace-id.md)).

```json
{
  "status": 422,
  "message": "Only ACTIVE upgrades can be completed",
  "path": "/api/upgrades/0f2c8f5a.../complete",
  "timestamp": "2026-01-14T09:13:40",
  "traceId": "65b2c0f4a1d3e8b7"
}
```

The `traceId` is also returned as the `X-Trace-Id` response header, and is the key that joins a
failure a user reports to the log lines that produced it.
