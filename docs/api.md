# API reference

Every endpoint below `/api` except `POST /api/auth/register` and `POST /api/auth/login` requires a
bearer token: `Authorization: Bearer <token>`. Tokens last 24 hours and are not refreshable.

Worked request and response bodies are in the [README's Usage section](../README.md#usage). This page
is the surface; [`requirements.md`](requirements/requirements.md) says what each capability must do,
and the `*ControllerTest` named against each area pins the status codes.

## Endpoints

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/register` · `POST /api/auth/login` · `GET /api/auth/me` |
| Health areas | `GET POST /api/health-areas` · `GET PUT DELETE /api/health-areas/{id}` |
| Upgrades | `GET POST /api/upgrades` · `GET PUT DELETE /api/upgrades/{id}` |
| Transitions | `POST /api/upgrades/{id}/…` — `plan` · `activate` · `pause` · `complete` · `abandon` · `reschedule` |
| Tracking config | `GET PUT /api/upgrades/{id}/tracking-config` |
| Progress | `GET POST /api/upgrades/{id}/progress` · `GET /api/upgrades/{id}/streak` · `GET /api/progress/today` · `GET /api/progress/week` |
| Reflections | `GET POST /api/upgrades/{id}/reflections` |
| Reminders | `GET POST /api/upgrades/{id}/reminders` · `PUT DELETE /api/reminders/{id}` |
| Notifications | `GET /api/notifications` · `GET /api/notifications/unread-count` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all` |
| Dashboard | `GET /api/dashboard` |
| Real-time | STOMP over `/ws`; the client subscribes to `/user/queue/notifications` |

`GET /api/upgrades` filters on `status`, `type`, `areaId` and `difficulty`.

## A created upgrade, in full

`POST /api/upgrades` returns `201`. Nulls are serialised rather than omitted, so the body always
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
| `400` | Failed validation, an unbindable body, or a parameter that will not convert |
| `401` | Credentials rejected — the response never says which half was wrong |
| `403` | Access denied |
| `404` | Not found, **including** a record belonging to another user: ownership is enforced by the query being user-scoped, so a foreign row is indistinguishable from a missing one |
| `409` | A duplicate progress entry for the same upgrade and date, or a stale optimistic-lock `version` |
| `422` | A business rule refused the operation — an illegal lifecycle transition, or a fourth concurrent `HARD` upgrade |
| `405` `415` `406` | The status Spring defines for the framework exception |
| `500` | A genuine server fault. Carries the status and a trace id, and nothing else |

## Error body

Every failed request returns the same shape. `fieldErrors` appears only on validation failures and
`traceId` only when the request was traced; both are omitted otherwise.

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
