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
attempt failed, and it gives up only when the database cannot be reached.**

- **Carry on.** A `RuntimeException` from `create` is caught for that item only. The sweep moves on to
  the next item. An `Error` is not caught and still ends the sweep where it stands.
- **Warn by ids, and by the exception's type only.** Each failure writes one WARN line with
  `event=notification.create-failed`, the job, the ids (`reminderId`, `upgradeId`, `userId`, or
  `userId` alone for the nudge) and `exception=<simple class name>`.
  - The line carries no stack trace and no message. A driver's message can quote the row it refused,
    which NFR-6 forbids. The run's single ERROR line, below, still carries the first failure's
    message, as it did before this change. Whether NFR-6 allows that is
    [#139](https://github.com/NaimElijah/UpHealther/issues/139).
  - One sweep's failures must not mean one stack trace each.
  - The name means "create threw", not "nothing was stored". A push failure other than
    `MessagingException` surfaces after the commit.
- **Report what the run did.** The run's INFO line gains `failed` and `skipped`. `fired` and `nudged`
  now count notifications actually created, not attempts.
- **Then fail.** The first failure is rethrown once the INFO line and the orphan WARN are written:
  - `JobMetrics` counts the run `failed`;
  - Spring's scheduler logs that failure's stack trace once, at ERROR. That is the level ADR-010 gives
    a run that threw, and lost reminders are never retried, so somebody has to act.
- **Give up when the database is out of reach, and only then.** A `@Transactional` call that cannot
  get a connection throws `CannotCreateTransactionException`, after the pool has waited out its
  timeout. Each further attempt would wait it out again on the shared thread, so the first such failure
  ends the attempts. The remaining items are counted as `skipped`.
  - Any other failure is a fact about its row, however many there are and however adjacent. Nothing
    orders a sweep: reminders are unordered, and users come from a `HashMap`.

## Consequences

- One bad row no longer costs every item after it. The log says how many items were sent, failed and
  skipped, and which ones failed.
- The metric vocabulary does not change. `failed` now means "the run did not do everything it
  should", and the INFO line tells a partial run from a total one.
- An outage mid-sweep costs one connection timeout on the shared thread, as it did before this change,
  not one per remaining item.
- An outage that does not surface as a failure to begin a transaction does not stop the sweep. A
  connection dropped mid-statement fails only that item, and the next item's begin is what trips the
  stop.
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
- **Attach the later failures to the rethrown one as suppressed.** Rejected: nothing bounds how many
  rows can fail, and Spring would print every attached stack trace in one ERROR entry.
- **No stop at all.** Rejected: an outage would hold the one scheduler thread for 30 s per remaining
  item, and every other job would wait behind it.
- **Stop after three failures in a row.** This was the first version on #138, rejected in its review.
  A count cannot tell an outage from three bad rows that happen to be adjacent. Tripping it skipped
  everybody after them, and it let an outage cost three timeouts, about 90 s, which is long enough
  to push the next reminder run past its minute.
- **A bigger scheduler pool or a shorter Hikari timeout.** Rejected for this: both change every job and
  every request to fix two loops.
- **The same change in `UpgradeOverdueScheduler`.** Rejected for now. That sweep re-scans every
  morning, and its notification is created once per upgrade, so a failure there delays an announcement
  rather than losing it.

## When to revisit

- **A second scheduler needs the same loop.** Move `Deliveries` to `common` rather than copying it.
- **The persistence stack changes how an unreachable database surfaces.** For example, delayed
  connection acquisition, which #133 considers, would make the first statement fail instead of the
  transaction's begin. The stop has to match whatever the new signal is.
- **Alerting needs to tell a partial run from a total one** without reading logs. That is the point at
  which a third outcome earns its place.
- **An overdue upgrade fails to be announced on two consecutive days.** `findByStatus` has no ordering.
  If the scan order is stable, one row that always fails would block the same upgrades after it every
  day, and the overdue sweep would need this change too.
