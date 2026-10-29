# ADR-058: A PR into `dev` merges only once its checks ran on the latest `dev`

- **Status:** Accepted
- **Date:** 2026-10-29
- **Supersedes in part:** [ADR-043](ADR-043-ci-is-a-merge-gate.md), its "`strict_required_status_checks_policy`
  is off", for `dev` only
- **Scope:** repository ruleset 26158509, "CI on the latest dev : to dev". It is a GitHub setting, not a
  file: this record is where the tree says it exists. Ruleset 25310599 is unchanged. NFR-12 in
  `docs/requirements/requirements.md`.
- **Issue:** [#336](https://github.com/NaimElijah/UpHealther/issues/336), scan 02 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding I2.10. The owner's decision.

## Context

ADR-043 made CI a merge gate: ruleset 25310599 requires the four `ci.yml` jobs on every merge into `main`
and `dev`, with no bypass. It left `strict_required_status_checks_policy` off: "Requiring the branch to be
up to date would re-run CI every time `dev` moves, and the merge commit is built again on push anyway."

Scan 02 found the case that reasoning lets through:
1. PR A renames a method.
2. PR B, green against the `dev` it was cut from, still calls the old name.
3. Both merge, each with green checks.
4. `dev`'s push run then goes red, and no PR is there to blame.

The push run does report it, but only after the broken commit is on `dev`, where every later branch is cut
from it. Scan 01 merged five PRs in a row that touched the same `requirements.md` cells and the same test
classes. Each was brought up to date by hand ("Merge origin/dev into …"), but nothing required it.

Turning strict on in 25310599 would apply to `main` too, and there it would block every release. Each
dev → main promotion leaves a merge commit on `main` that `dev` never gets. On 2026-10-29, `dev` was 42
commits behind `main`, all of them promotion merges. GitHub's strict check would therefore mark every
promotion PR as behind, until `main` was merged back into `dev` with a PR of its own.

## Decision

**A second ruleset, 26158509, "CI on the latest dev : to dev", requires the same four checks on `dev`
only, with `strict_required_status_checks_policy: true` and no bypass. Ruleset 25310599 stays as ADR-043
left it, for both branches.**

- **Rules stack.** `gh api repos/NaimElijah/UpHealther/rules/branches/dev` lists both rulesets'
  `required_status_checks`: 25310599 with strict off, and 26158509 with strict on. GitHub applies the
  stricter. `…/rules/branches/main` lists 25310599 alone.
- **The same four contexts,** each pinned to the GitHub Actions app (`integration_id` 15368), as in
  25310599.
- **Who can bypass it:** nobody. It has no bypass actors, and `current_user_can_bypass` reads `never`.
- **Proof, on 2026-10-29, as the ruleset was created.**
  - #351, a Dependabot PR 157 commits behind `dev`, went from `mergeStateStatus` `CLEAN` to `BEHIND`.
  - #363, which had just merged `dev` in, stayed `CLEAN`.

## Consequences

- **`dev` no longer takes a PR whose checks ran on an older `dev`.** Two PRs that pass alone, and break
  once combined, now meet on the second one's branch, before the merge.
- **Every PR into `dev` merges the latest `dev` in before it can merge.** A `BEHIND` PR merges
  `origin/dev` and waits for a fresh run, about ten minutes with the image check. When several PRs land
  close together, each one after the first waits for that run.
- **Dependabot's PRs go behind as `dev` moves.** Each one needs a rebase or a merge of `dev` before it
  can merge. A `@dependabot rebase` comment does it.
- **Promotions are unaffected.** A dev → main PR is held only to ADR-043's rule. Its content already ran
  CI on `dev`'s push.
- **A job rename now edits two rulesets.** Renaming, adding or removing a `ci.yml` job means changing
  the required list in both 25310599 and 26158509, just before that PR merges. The project CLAUDE.md
  and `ci.yml`'s header say so.

## Alternatives considered

- **Strict on in 25310599, for both branches, with a main → dev back-merge after every promotion.**
  Rejected by the owner. It adds a PR and a CI run to every release, to protect `main`, which only ever
  receives `dev`.
- **Leave strict off, and record why.** Rejected by the owner, who wants the overlap caught before it
  reaches `dev`.
- **Split 25310599 into a `dev` ruleset and a `main` ruleset.** Rejected. It rewrites a ruleset that
  ADR-043, NFR-12 and several records name, where adding one leaves all of them true.

## When to revisit

- **Promotions stop leaving merge commits that `dev` lacks,** for example by fast-forwarding `dev` to
  `main` after each one. Strict can then move into 25310599, and this ruleset can go.
- **The waiting delays merges more than red `dev`s cost,** for example once many PRs land a day.
- **A job in `ci.yml` is renamed, added or removed:** both rulesets' lists change.
