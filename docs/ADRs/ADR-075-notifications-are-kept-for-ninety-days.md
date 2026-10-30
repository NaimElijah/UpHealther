# ADR-075: Notifications are kept for ninety days

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `NotificationRetentionService` and `NotificationRetentionScheduler` in `notification`, and
    `NotificationRepositoryPort.deleteCreatedBefore`.
  - `backend/src/main/resources/db/migration/V17__index_notifications_by_creation.sql`.
  - `app.notifications.schedules.retention` in `application.yml`.
  - NFR-60 in `docs/requirements/requirements.md`.
- **Issue:** [#340](https://github.com/NaimElijah/UpHealther/issues/340), scan 06 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D6.8. The owner decided the period
  on 2026-10-30, in
  [the D6.8 decision](https://github.com/NaimElijah/UpHealther/issues/340#issuecomment-6531580577).
  Follows [ADR-072](ADR-072-every-migration-bounds-its-lock-wait.md) for its index.

## Context

Nothing deleted a notification. The sweeps write one per due reminder per day, and one per user per day for
the check-in nudge, so `notifications` only grew. The API serves the newest fifty of a user's notifications
and nothing older, so the rest were kept without being readable. Their messages quote upgrade titles, which
users write, so what was kept was personal data with no purpose left. `requirements.md` had no rule on it.

The sessions table had the same shape and already has a nightly job, `AuthSessionCleanupScheduler`, that
deletes what nothing can use.

## Decision

**A notification is deleted once it is ninety days old.**

1. **A nightly job deletes them.** `NotificationRetentionScheduler` runs on
   `app.notifications.schedules.retention`, 03:45 UTC by default (`NOTIFY_RETENTION_CRON` overrides it), a
   quarter of an hour after the session cleanup. It is a trigger, timed and counted by `JobMetrics` under
   `notification.retention`, as the other jobs are, and it logs one INFO line only when it removed something.
2. **The cutoff is the server's clock less ninety days.** `NotificationRetentionService` reads the injected
   `Clock`, in UTC (ADR-020), and deletes what was created strictly before it, so a notification exactly ninety
   days old stays one more night.
3. **It deletes in bounded batches.** One statement removes at most a thousand rows, oldest first, each in a
   transaction of its own, and the job repeats until a batch comes back short. A first run against a large
   backlog, or a night after the job was down, holds its locks briefly and often rather than for the whole
   table. A batch that committed stays deleted if a later one fails.
4. **An index on `notifications (created_at)` serves it** (V17). Without one, each batch would scan the table
   for its oldest rows. The check-in sweep's dedup read, `findUserIdsNotifiedAfter`, filters on the same column
   for one day's rows, and the index serves that too.
5. **The period and the batch size are constants** in `NotificationRetentionService`. The period is a product
   decision, recorded here, not an operator's setting; only the schedule is configuration, as the session
   cleanup's is.
6. **The retention service is not `NotificationService`.** A controller holds that one, and this deletes every
   account's rows; only the job holds the retention service.

## Consequences

**What this makes easy**

- **The table stops growing without bound**, and the user-written text in old messages goes with it.
- **The rule can be checked.** NFR-60 names the tests, and the boundary is a unit test with a fixed clock.

**What this makes hard**

- **A user who returns after more than ninety days finds nothing older.** That is the decision, not an
  accident, and the inbox never showed more than fifty anyway.
- **Correctness depends on the job running.** If it stops, old notifications stay until it runs again; the
  run counter shows it stopped (NFR-25).
- **The period is in code.** Changing it is a release, which is the point of recording it here.

## Alternatives considered

- **Thirty days.** Rejected by the owner: a user who comes back after a month would find an empty list.
- **The newest fifty per user**, which is what the API shows. Rejected: it needs a per-user ranking query on
  every run, and an active user's notifications could disappear within days.
- **Defer to the privacy notice** ([#222](https://github.com/NaimElijah/UpHealther/issues/222)). Rejected:
  that leaves user-written text kept for ever until then.
- **One `DELETE` for everything past the cutoff.** Simpler, and fine for one night's rows. Rejected: the first
  run, or one after the job was down, would delete an unbounded number of rows in one transaction.

## When to revisit

- **The privacy notice (#222) states a retention period** for this data. The constant then follows it, and
  this record is superseded.
- **A user asks for a longer history**, or a feature needs notifications older than ninety days. Then the
  period, or what is kept of a notification, is a product decision again.
