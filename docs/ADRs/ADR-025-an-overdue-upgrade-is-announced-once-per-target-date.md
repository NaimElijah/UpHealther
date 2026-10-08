# ADR-025: An overdue upgrade is announced once per target date, judged by when the notice was sent

- **Status:** Accepted
- **Date:** 2026-10-08
- **Scope:** `UpgradeOverdueDetected`, `NotificationEventListener.onOverdue`, `NotificationService` and the
  notification repository's dedup query. No migration, no API change, no new dependency.
- **Issue:** [#103](https://github.com/NaimElijah/UpHealther/issues/103)

## Context

`UpgradeOverdueScheduler` runs every morning and publishes `UpgradeOverdueDetected` for every `ACTIVE`
upgrade past its target end date. It rediscovers the same overdue upgrade on every run, so BR-11 said
the upgrade is announced once, however many times the sweep finds it.

That "once" was permanent. `NotificationService.createOncePerUpgrade` refused a second
`UPGRADE_OVERDUE` notice if one had ever existed for the upgrade, read or not, and notifications are
never deleted. A user who moved the target date later through an edit, then missed that date too, was
never told again. The dashboard's overdue list is computed live and was never affected.

The owner decided on #103 that each missed target date is announced once.

Three facts make the date that was announced recoverable without storing it:
- `HealthUpgrade.isOverdue(asOf)` is `asOf.isAfter(targetEndDate)`, so a notice about date D cannot be
  created before `D + 1` at 00:00.
- The sweep's "today" and the notice's `createdAt` both come from the one injected clock, which keeps
  UTC ([ADR-020](ADR-020-the-server-keeps-time-in-utc.md)).
- `UPGRADE_OVERDUE` is created by this listener alone.

## Decision

**A notice counts as having announced the current target date if it was created after that date
passed, that is, at or after the start of the following day.**

- **`UpgradeOverdueDetected` carries `targetEndDate`**, the date that was missed. The sweep already had
  it, and the listener cannot ask "which date?" any other way.
- **`NotificationEventListener.onOverdue` passes `targetEndDate.plusDays(1).atStartOfDay()`** as the
  bound. A why-comment there states the invariant this relies on.
- **`NotificationService.createUnlessNotifiedSince(…, since)`** replaces `createOncePerUpgrade`. The
  service stays generic: the caller knows when the fact it announces began, and the service does not.
- **The repository asks `existsByUserIdAndRelatedUpgradeIdAndTypeAndCreatedAtGreaterThanEqual`**, a
  derived query. The bound is inclusive. `NotificationPersistenceIT` proves it against PostgreSQL, on
  both sides of the bound and for another upgrade and another type. `OverdueAnnouncementIT` drives the
  rule itself, from the listener through the service into PostgreSQL.

## Consequences

- A target date moved later and missed again is announced again, once.
- A target date moved earlier, or to any date that had already passed when the last notice was sent, is
  covered by that notice. The user was told the upgrade was overdue at a time when the new date was
  already behind them, so nothing new has happened.
- Existing rows need no backfill. Their `createdAt` already means what the new query reads.
- **The rule depends on how the notice is created.** If anything ever creates `UPGRADE_OVERDUE` before
  its date has passed, or stamps `createdAt` from a different clock, the inference breaks silently. The
  listener's comment and this record are the guard. No test can see a future second producer.
  - Today `NotificationService.create` is the only path that builds a notification. It sets
    `createdAt` from the injected clock, which
    `NotificationServiceTest.GivenANewNotification_WhenItIsCreated_ThenItIsStampedFromTheInjectedClock`
    pins.
  - `Notification`'s `@PrePersist` fallback reads `LocalDateTime.now()` in the host's zone. It never
    fires today, because every save sets `createdAt` first. It is part of the entity-timestamp deviation
    against NFR-15 that [#51](https://github.com/NaimElijah/UpHealther/issues/51) tracks, and fixing
    that removes this gap too.

## Alternatives considered

- **Store the announced date on the notification.** A nullable `related_date` column, filled for
  `UPGRADE_OVERDUE` and compared on equality. It is explicit and needs no invariant. Rejected for now:
  it is a migration and an entity field that one notification type out of eleven would use, to state
  something `createdAt` already implies.
- **Delete the old notice when the target date changes.** Rejected: it couples the upgrade context's
  edit to the notification context's storage, and it removes from the user's inbox a notice they may
  not have read.
- **Once, ever.** This was the behaviour before, stated as the rule. The owner rejected it on #103.

## When to revisit

- **A second producer of `UPGRADE_OVERDUE`** appears, or one that can fire before the date has passed,
  such as a "due tomorrow" warning reusing the type. Store the date explicitly at that point.
- **Another notification type needs dedup on a value rather than on time.** That is when a
  `related_date` (or a more general key) column earns its migration.
- **The clock that stamps notifications and the one that judges overdue ever differ**, for example a
  per-user time zone. The bound then has to be computed in the zone that judged the date.
