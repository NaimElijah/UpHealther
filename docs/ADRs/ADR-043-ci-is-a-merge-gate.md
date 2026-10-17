# ADR-043: CI is a merge gate — a second ruleset, with no bypass, requires all three checks

- **Status:** Accepted
- **Date:** 2026-10-17
- **Scope:** repository ruleset 25310599, "CI must pass : to main & dev". It is a GitHub setting, not
  a file: this record is where the tree says it exists. Also `.github/workflows/ci.yml`.
- **Issue:** [#163](https://github.com/NaimElijah/UpHealther/issues/163), and its duplicate
  [#282](https://github.com/NaimElijah/UpHealther/issues/282).

## Context

CI runs on every push and pull request to `main` and `dev` (NFR-12), and the repository baseline says a
red pipeline blocks the merge. Nothing made it block.

The one ruleset, 18678702, "PR-only + protected : to main & dev", requires the following, and no
passing check:

- a pull request;
- `allowed_merge_methods: ["squash"]`;
- linear history.

Its one bypass actor is the Admin role, with `bypass_mode: always`. Every merge here goes through that
bypass. History is merge commits, and `.git-blame-ignore-revs` depends on that (ADR-037), while the
ruleset allows only squash.

So adding `required_status_checks` to that ruleset would have bound everybody except the one person
who merges. When #163 was filed, every merged PR had green checks, so the gate was habit.

GitHub evaluates every ruleset that targets a branch, and a bypass actor bypasses only the rules of the
ruleset that lists it. That is the property this decision rests on, and it was tested here rather than
assumed (below).

## Decision

**A second ruleset, 25310599, "CI must pass : to main & dev", is active on `main` and `dev`.**

- **What it holds:** only `required_status_checks`, for the three jobs in `ci.yml`:
  - `Backend - Build & Test`
  - `Diagrams - Drift check`
  - `Frontend - Build & Lint`
- **Who can bypass it:** nobody. It has no bypass actors, and `current_user_can_bypass` reads `never`.
- **Merge commits** still pass ruleset 18678702 through its bypass, as before.
- **Each check is pinned to the GitHub Actions app** (`integration_id` 15368), so a status posted under
  the same name by anything else does not satisfy it.
- **`strict_required_status_checks_policy` is off.** Requiring the branch to be up to date would re-run
  CI every time `dev` moves, and the merge commit is built again on push anyway.

**Proof, on 2026-10-17, with throwaway PR #288 into `dev`.** It drifted one generated diagram, so only
the diagram check could fail. A plain `gh pr merge 288 --merge`, run as the admin, was refused three
times, each with *"the base branch policy prohibits the merge"* and `mergeStateStatus` `BLOCKED`:

1. while all three checks were pending;
2. with the diagram check red and the other two pending;
3. with the diagram check red and the other two green.

`gh` offered `--admin` each time. It was not tried, because a success would have put a red commit on
`dev`. The PR was closed unmerged, and its branch deleted.

**The workflow token is declared `contents: read`** at the top of `ci.yml`. That keeps the restriction
with the workflow rather than in a repository setting.

## Consequences

- **A red or pending check blocks every merge into `main` and `dev`, the owner's included.** NFR-12 now
  says so.
- **The escape hatch is disabling ruleset 25310599**, in the repository settings. That is deliberate and
  visible in the settings' history, unlike a click in the merge box. It is the right tool only for a
  broken CI, never for a red change.
- **A job's `name:` is now part of the gate.**
  - A required check that never reports leaves every PR waiting ("Expected — Waiting for status to be
    reported").
  - So renaming, adding or removing a job means editing the ruleset just before merging the PR that
    does it. The ruleset is a setting, not a file, so it cannot travel in the PR, and until it changes
    that PR waits for its old name.
  - Every other open PR then needs a new run, from a push or a close and reopen, to report the new name.
    A re-run reuses the old merge ref, and so the old workflow.
  - `ci.yml` says so above its jobs.
- **When CI does not fire on a PR, the PR waits.** That has happened intermittently on a new branch's
  first PR. Closing and reopening it re-triggers the run.
- **The admin can no longer push straight to `main` or `dev`.**
  - Until now ruleset 18678702's `always` bypass covered its pull-request rule too. Its rule-suites log
    the admin's updates to `dev` as `bypass`.
  - Ruleset 25310599 has no bypass. By GitHub's documentation, a direct push must now carry a commit
    whose three checks already passed on another ref; this was not tested here.
  - So a hotfix goes through a PR like any other change, or through disabling this ruleset.

## Alternatives considered

- **Add `required_status_checks` to ruleset 18678702** (#163's option 1). Rejected. Its `always`
  bypass covers every rule in it, so for the admin, who does all the merging, the gate would be
  advisory.
  - **Revisit:** if a second person with merge rights joins. Even then, this ruleset keeps binding
    everyone.
- **Remove the bypass from ruleset 18678702** (#163's option 3). Rejected.
  - The check would bind, but so would `allowed_merge_methods: ["squash"]` and linear history.
  - Every merge would become a squash, `dev` and `main` would diverge on each promotion, and the
    `.git-blame-ignore-revs` SHAs would stop resolving.
  - **Revisit:** if the project moves to squash merges deliberately.
- **Remove the bypass from ruleset 18678702, and change its merge rules to match practice:**
  `allowed_merge_methods: ["merge"]`, no `required_linear_history`, plus the required checks. This is
  the variant #163 hinted at. One ruleset would then bind everyone and keep merge commits. Rejected:
  - It rewrites the branches' whole protection to add one rule.
  - It makes every rule in it unbypassable for the admin at once: deletion, force-push and PR-only.
    The only escape from any of them would be disabling the protection itself, where today disabling
    25310599 lifts the check alone.
  - Keeping the gate in a ruleset of its own lets each be changed, or switched off, without the other.
  - **Revisit:** if the owner wants 18678702 to describe the merge method actually used. That variant
    would then replace both rulesets.
- **Also cancel superseded runs (`concurrency:`), bound each job's time (`timeout-minutes:`), and pin
  actions by commit SHA.** Left out, as #163 marks all three optional; none is needed for the gate.
  - **Revisit `timeout-minutes`** if a job ever hangs: a hung required check now holds a PR for the
    default 360 minutes.
  - **Revisit `concurrency`** if queued runs start delaying merges.
  - **Revisit SHA pinning** when something keeps the SHAs current, such as Dependabot (#164).

## When to revisit

- **A job in `ci.yml` is renamed, added or removed.** The ruleset's list changes just before that PR
  merges.
- **CI is broken for a reason unrelated to the change being merged,** often enough that disabling the
  ruleset becomes routine. Fix CI; do not normalise the escape hatch.
- **The merge method changes,** which reopens the alternatives above.
