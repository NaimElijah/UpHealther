# ADR-023: A notification sweep carries on past a failed save, and fails the run at the end

- **Status:** Accepted
- **Date:** 2026-10-07
- **Scope:** `NotificationScheduler`: `dispatchReminders` and `notifyDailyCheckin`. Builds on
  [ADR-010](ADR-010-structured-logging-and-a-level-policy.md) and
  [ADR-012](ADR-012-metrics-through-a-prometheus-scrape-endpoint.md). `JobMetrics` is unchanged.
- **Issue:** [#136](https://github.com/NaimElijah/UpHealther/issues/136), found in the review of #135

## Context

Both notification sweeps create one notification per item in a plain loop:
- the reminder dispatch creates one per due reminder on an active upgrade;
- the check-in nudge creates one per user who has neither been nudged nor logged today.

Each `NotificationService.create` is its own transaction. When one save threw (a lock timeout, a
dropped connection, a pool exhausted for a moment), the exception left the loop, and everything after
that item went with it:
- **Reminders.** The rest of that minute's due reminders were never sent. A minute does not repeat, so
  nothing ever retried them.
- **The nudge.** The users after the failing one were not nudged that evening.
- **The run's INFO line.** It was skipped too, so the log could not say how many were lost. NFR-25
  asks every run to record what it did.

`JobMetrics` has two outcomes, `ok` and `failed`, and decides between them only by whether the body
returned. A partly failed run is neither.

Two facts about the runtime bound any answer:
- All four `@Scheduled` jobs share Spring Boot's default scheduler, which has one thread.
- Hikari waits its default connection timeout, 30 s, before giving up on a connection.

## Decision

**Each notification in a sweep is attempted on its own. The run still ends as a failure when any
attempt failed, and it gives up after three failures in a row.**

- **Carry on.** A `RuntimeException` from `create` is caught for that item only. The sweep moves on to
  the next item. An `Error` is not caught and still ends the sweep where it stands.
- **Warn by ids, and by the exception's type only.** Each failure writes one WARN line with
  `event=notification.create-failed`, the job, the ids (`reminderId`, `upgradeId`, `userId`, or
  `userId` alone for the nudge) and `exception=<simple class name>`.
  - The line carries no stack trace and no message. A driver's message can quote the row it refused,
    which NFR-6 forbids.
  - One sweep's failures must not mean one stack trace each.
  - The name means "create threw", not "nothing was stored". A push failure other than
    `MessagingException` surfaces after the commit.
- **Report what the run did.** The run's INFO line gains `failed` and `skipped`. `fired` and `nudged`
  now count notifications actually created, not attempts.
- **Then fail.** The first failure is rethrown once the INFO line and the orphan WARN are written:
  - `JobMetrics` counts the run `failed`;
  - Spring's scheduler logs that failure's stack trace once, at ERROR. That is the level ADR-010 gives
    a run that threw, and lost reminders are never retried, so somebody has to act.
- **Give up after three in a row.** `MAX_CONSECUTIVE_FAILURES = 3`. One failure is a bad row. Several
  in a row is the database being gone, and each further attempt would hold the shared thread for a
  connection timeout. The remaining items are counted as `skipped`, not attempted. A success resets the
  count, so the limit bounds a streak, not a total.

## Consequences

- One bad row no longer costs every item after it. The log says how many items were sent, failed and
  skipped, and which ones failed.
- The metric vocabulary does not change. `failed` now means "the run did not do everything it
  should", and the INFO line tells a partial run from a total one.
- An outage mid-sweep costs at most three connection timeouts (about 90 s) on the shared thread, not one
  per remaining item.
- Skipped items are counted, not named. Who was skipped is not in the log.
- **Unverified:** Spring's ERROR line for the rethrown failure is probably written outside the run's
  observation, so it may carry no trace id. Reading the framework suggests this; nothing has run to
  confirm it. The WARN lines are written inside it.

## Alternatives considered

- **Leave it, and say so.** A database failure that hits one row probably hits the next too. Rejected:
  a single bad row is a real case, and the run's INFO line was lost along with the reminders.
- **Catch, log, and count the run `ok`.** Rejected: the run then reports success while reminders are
  lost for good. That is the silent sweep `JobMetrics` exists to prevent.
- **A third outcome, `partial`.** Rejected: it widens a closed tag set that dashboards and alerts read
  (ADR-012) and changes `JobMetrics`' API, all to split a case the INFO line already distinguishes.
- **Attach the later failures to the rethrown one as suppressed.** Rejected: the three-in-a-row limit
  bounds a streak, not the total, and Spring would print every attached stack trace in one ERROR entry.
- **No limit.** Rejected: an outage would hold the one scheduler thread for 30 s per remaining item, and
  every other job would wait behind it.
- **A bigger scheduler pool or a shorter Hikari timeout.** Rejected for this: both change every job and
  every request to fix two loops.
- **The same change in `UpgradeOverdueScheduler`.** Rejected for now. That sweep re-scans every
  morning, and its notification is created once per upgrade, so a failure there delays an announcement
  rather than losing it.

## When to revisit

- **A second scheduler needs the same loop.** Move `Deliveries` to `common` rather than copying it.
- **The scheduler pool size or Hikari's connection timeout changes.** The limit of three was chosen
  against one thread and 30 s.
- **Alerting needs to tell a partial run from a total one** without reading logs. That is the point at
  which a third outcome earns its place.
- **An overdue upgrade fails to be announced on two consecutive days.** `findByStatus` has no ordering.
  If the scan order is stable, one row that always fails would block the same upgrades after it every
  day, and the overdue sweep would need this change too.
