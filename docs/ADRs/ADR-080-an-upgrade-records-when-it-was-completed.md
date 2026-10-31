# ADR-080: An upgrade records when it was completed

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:**
  - `backend/` — `V19__upgrade_completed_at.sql`, `V20__upgrade_completed_at_follows_status.sql`,
    `HealthUpgrade.completedAt` and `complete(LocalDateTime)`, `UpgradeService.complete`, and the
    "recently completed" order in `DashboardAggregationService`.
  - Not the wire: no response record carries the column.
- **Issue:** [#342](https://github.com/NaimElijah/UpHealther/issues/342), scan 08 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D3.1, with the owner's decision
  to add the column now. Related: [#263](https://github.com/NaimElijah/UpHealther/issues/263), the
  planned record of an upgrade's lifecycle.

## Context

FR-27 promises a dashboard that shows a user's recently completed upgrades. The dashboard ordered them by
`updated_at`, newest first, and kept five. But `updated_at` is stamped on every write, and FR-13 lets a
completed upgrade be edited: renaming one finished a year ago put it back at the top of the list, above
one finished yesterday. The order said "recently edited" while the section said "recently completed".

Nothing else recorded the moment. `health_upgrades` holds an upgrade's current state only, and #263
plans a full history of its lifecycle, from which a completion time could be derived. #263 is a larger
piece of work, blocked on decisions about how features are computed, and FR-27 is wrong now.

## Decision

**`health_upgrades` gains a nullable `completed_at TIMESTAMP`, which the domain sets at the one
transition into COMPLETED, from the injected clock, and the dashboard orders recent completions by it.**

- **The column.** `V19` adds it as `TIMESTAMP`, as `updated_at` is, and the entity holds a
  `LocalDateTime`. It is null for every upgrade that has not been completed. COMPLETED is terminal (no
  transition leaves it), so the stamp never moves once set; an edit moves `updated_at` and leaves it.
- **The schema ties it to the status.** `V20` adds `health_upgrades_completed_at_check`,
  `(status = 'COMPLETED') = (completed_at IS NOT NULL)`, as V12 holds each value domain
  ([ADR-073](ADR-073-the-schema-refuses-what-the-domain-cannot-hold.md)): a stray write can neither
  mark an upgrade COMPLETED without a stamp nor stamp one that is not.
- **The clock.** `HealthUpgrade.complete(LocalDateTime at)` takes the moment, as the other transitions
  take their dates, and refuses a null one. `UpgradeService.complete` passes
  `LocalDateTime.now(clock)`, the same instant the `HealthUpgradeCompleted` event carries, so the two
  never disagree. The injected clock
  keeps UTC ([ADR-020](ADR-020-the-server-keeps-time-in-utc.md)); `updated_at`, stamped by a persistence
  hook in the JVM's zone, is not ([#51](https://github.com/NaimElijah/UpHealther/issues/51)).
- **The backfill is approximate.** A row completed before V19 has no record of when, so V19 copies its
  `updated_at`. That is exact for an upgrade nobody edited after completing it, and for one edited since
  it is the order the dashboard already showed, so no user sees the list change on the day it ships.
- **Not on the wire.** `UpgradeDto` does not carry the column: no screen shows a completion time yet,
  and adding one is a change of its own.

## Consequences

**What this makes easy**

- **"Recently completed" means what it says.** An edit to a finished upgrade no longer reorders it.
- **A completion time exists for anything that wants one** later, a details page or #263's features,
  without a migration of its own.

**What this makes hard**

- **Two sources of truth, once #263 lands.** A lifecycle history would also know when each upgrade was
  completed. #263 may derive the column from its events, or supersede this record and drop it; either
  way the decision is #263's, and this ADR is to be marked superseded if it does.
- **Old rows carry a guess.** A completion before V19 that was edited later is dated at the edit, and
  nothing marks which rows were guessed. A feature that needs exact completion times cannot trust rows
  stamped before V19 ran.
- **A status change written past the domain must set the stamp too.** V20 refuses a manual `UPDATE`
  that marks an upgrade COMPLETED without `completed_at`, or that moves one out of COMPLETED and keeps
  it, so a data fix states both columns. In exchange the dashboard orders no null: a COMPLETED row
  without the stamp cannot exist, and an aggregate built that way in memory fails the sort loudly.
- **The tie is cheap only while the table is small.** V20 validates in the same transaction as the
  `ADD CONSTRAINT`, so the scan of `health_upgrades` runs while the ADD's ACCESS EXCLUSIVE lock is
  still held: harmless with no production data yet
  ([#206](https://github.com/NaimElijah/UpHealther/issues/206)), but a later change to the rule on a
  populated table validates in a migration of its own (ADR-072).

## Alternatives considered

- **Restate FR-27 as "recently updated completions".** Rejected by the owner: the requirement is the one
  users expect, and the order was the bug.
- **Wait for #263's lifecycle history.** Rejected for now: #263 is blocked on how features are computed
  and is much larger, and FR-27 is wrong today. One column costs little to supersede later.
- **Stamp `completed_at` in a persistence hook.** Rejected: the transition is where the domain knows the
  upgrade became COMPLETED, and a hook would read the JVM's clock, which tests cannot inject.

## When to revisit

- **#263 lands a lifecycle history.** Decide there whether `completed_at` is derived from it or dropped,
  and supersede this ADR with that decision.
- **A screen or an API needs the completion time.** Exposing it on `UpgradeDto` is then a wire change of
  its own, with `npm run generate:wire-types`.
