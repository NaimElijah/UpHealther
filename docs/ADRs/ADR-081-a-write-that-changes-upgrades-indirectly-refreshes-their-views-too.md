# ADR-081: A write that changes upgrades indirectly refreshes their views too

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:** `frontend/src/pages/HealthAreasPage.tsx`'s three mutations, through
  `frontend/src/lib/upgradeViews.ts`. No new dependency, and no backend change.
- **Issue:** [#342](https://github.com/NaimElijah/UpHealther/issues/342), scan 08 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D4.1. Extends
  [ADR-029](ADR-029-a-write-to-an-upgrade-refreshes-every-view-of-upgrades.md), whose Decision lists
  only the writes to an upgrade itself.

## Context

ADR-029 made every write that changes an upgrade call `refreshUpgradeViews`, and named those writes: a
transition, a create, and a tracking-configuration save. Each of them is a write *to* an upgrade.

A health area's writes change what the upgrade views show without being writes to an upgrade:

- **A delete detaches the upgrades filed under the area.** The foreign key is `ON DELETE SET NULL`
  (V18), so the server clears each one's `areaId`. The page invalidated `['healthAreas']` alone. An
  upgrade's details page reopened within the thirty-second staleness window still held the deleted
  id, sent it back on the next edit, and the save was refused as an unknown health area, for a field
  the picker showed as empty. The dashboard's By Area also kept the deleted area.
- **A create or a rename changes the dashboard.** Its By Area names every area the user owns, empty
  ones included, so a new area was missing from it and a renamed one kept its old name, for up to
  thirty seconds.

## Decision

**A write that changes upgrades indirectly refreshes the views that show them, as a write to an upgrade
does.**

- **Deleting a health area calls `refreshUpgradeViews`**, beside invalidating `['healthAreas']`. It
  changes the upgrades filed under the area, so every key that shows upgrades is marked stale, the
  dashboard included.
- **Creating or renaming a health area invalidates `['dashboard']`** beside `['healthAreas']`. It
  changes no upgrade, so it does not call the helper, but the dashboard names every area.
- A future write of this kind, one whose server-side effect reaches upgrades through a relation, calls
  the helper too, and says why beside the call.

ADR-029's mechanism is unchanged: the same helper, the same keys, the same refetch of what a mounted
page reads and nothing more.

## Consequences

**What this makes easy.**

- An upgrade filed under a deleted area is edited without the deleted id being sent back.
  `upgradeViews.test.tsx` pins that, and that By Area drops a deleted area and shows a new or renamed
  one.
- The rule for a new write is one question: does it change what an upgrade view shows, directly or
  through the server? If so, it calls the helper.

**What this makes hard.**

- **An indirect effect has to be noticed.** Nothing in the code links a health area's delete to the
  upgrades it detaches; only the migration says so. A new relation of the same kind is stale again
  until somebody adds the call, and nothing fails until a test like `upgradeViews.test.tsx` covers it.
- **An area delete is coarse, as every write under ADR-029 is.** It marks every upgrade's details page
  stale, not only those filed under the area, at the cost of one request when each is next opened.

## Alternatives considered

- **Patch the cached upgrades with `setQueryData`**, clearing the deleted area's id in each list and
  details entry. Rejected for ADR-029's reason: the dashboard's By Area is computed by the server, and
  patching some caches while refetching others would be two ways of doing one thing.
- **Invalidate only the keys the area's upgrades appear under.** Rejected: the page does not know which
  upgrades were filed under the area without reading them, and the saving is one request per closed
  page.
- **Leave it to the staleness window.** Rejected: within it, an edit sends the deleted id and is
  refused, for a field the picker shows as empty, which reads as a fault the user cannot fix.

## When to revisit

- **A list paginates** ([#62](https://github.com/NaimElijah/UpHealther/issues/62)), as for ADR-029: a
  whole-prefix invalidation then refetches pages the user is not looking at.
- **Health areas gain a write that changes their upgrades in another way**, a merge or a move, say. It
  then needs its own place in this rule, and a test.
- **The server starts telling the client what a write changed**, through an event or the response. The
  client could then refresh exactly that, and this rule would be one of that mechanism's cases.
