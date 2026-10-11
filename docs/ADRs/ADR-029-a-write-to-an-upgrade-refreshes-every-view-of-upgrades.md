# ADR-029: A write to an upgrade refreshes every view of upgrades

- **Status:** Accepted
- **Date:** 2026-10-11
- **Scope:** `frontend/src/lib/upgradeViews.ts` and every mutation that changes an upgrade. No new
  dependency.
- **Issue:** [#189](https://github.com/NaimElijah/UpHealther/issues/189)

## Context

The frontend's server state lives in one TanStack Query cache. Its queries keep an answer fresh for
thirty seconds (`lib/queryClient.ts`), and a page opened again inside that window renders from the
cache without asking the server.

An upgrade is shown under several keys, one per kind of page:

| Key | Page |
|---|---|
| `['upgrades', <status>]` | the Backlog, Planned and Active pages, and the daily check-in, which picks each entry's input from the upgrade's tracking configuration |
| `['dashboard']` | the dashboard, which carries the active and planned upgrades in one response |
| `['upgrade', <id>]` | one upgrade's details |
| `['allUpgrades']` | the progress history, which looks up each entry's upgrade title |

Each page refreshed only its own key after a change. Pausing an upgrade on the dashboard therefore left
the Active page listing it as running for up to thirty seconds, with a Pause the server would refuse,
and missing it from the Paused section, the only place it can be resumed. The same held in every other
direction.

## Decision

**A write that changes an upgrade invalidates every key that shows one.**
`refreshUpgradeViews(queryClient)` holds the list of keys, and every such mutation calls it on success
in place of invalidating its own key. Such a write is a transition, a create, or a save of the
upgrade's tracking configuration, which the upgrade carries (FR-15).

- The keys are prefixes. `['upgrades']` covers every status, and `['upgrade']` every upgrade's details.
- Invalidation refetches only the queries a mounted page is using, and marks the rest stale, to be
  refetched when their page next opens. So a write costs one refetch for the page on screen, as before,
  and nothing for the pages that are closed.
- The helper returns the refetch's promise. A caller that must not act before the page shows the change
  can await it, as the backlog's plan dialog does.

## Consequences

**What this makes easy.**

- A new page that shows upgrades under a new key needs one line, in one place.
- A new mutation needs one call, and cannot refresh the wrong subset.
- `upgradeViews.test.tsx` drives two pages over one cache built as the app builds it. It pins every write
  that calls the helper today: the transitions made on the dashboard and on the Active, Planned and
  Backlog pages, a create on the Backlog, and a tracking-configuration save on an upgrade's page.

**What this makes hard.**

- **It is coarse.** Pausing one upgrade also marks every other upgrade's details page stale. That costs
  one extra request when one of them is next opened, and nothing before.
- **The list of keys is maintained by hand.** A page that shows upgrades under a key not on the list is
  stale again, and nothing fails until a test like `upgradeViews.test.tsx` covers it.
- **Another tab is still up to thirty seconds behind.** The cache belongs to the tab. A change made
  elsewhere reaches this tab when the window ends, or sooner if the page is reloaded.

## Alternatives considered

- **`staleTime: 0`.** Every page would refetch every time it opened, so it would never be stale. It was
  rejected because it gives up what the window is for: navigating between pages without a spinner and a
  request each time. That is a cost on every navigation, to fix something that happens only after a
  write.
- **Patching the other caches with `setQueryData`.** Each mutation would write the returned upgrade into
  every list and the dashboard. It was rejected because the dashboard's counts, today's list, the
  overdue list and the streaks are computed by the server. Recomputing them in the browser would
  duplicate domain rules (BR-2, FR-27) that already have one home. It is also more code per mutation for
  no visible gain over one refetch.
- **Invalidating from the lifecycle notifications on the WebSocket.** Each `UPGRADE_*` notification
  would invalidate the views, which would also cover a change made in another tab. It was rejected *for
  this change*: it ties freshness to a channel whose delivery is best-effort (ADR-028), and it does
  nothing for the writes no notification follows, such as an edit or a create. It could be added on top
  of this decision later.

**Revisit when** a list paginates ([#62](https://github.com/NaimElijah/UpHealther/issues/62)), so that
invalidating a whole prefix refetches pages the user is not looking at. Or when the extra request after
a write becomes visible in use. Or when keeping another tab current becomes a requirement, which is the
case for adding the WebSocket route.
