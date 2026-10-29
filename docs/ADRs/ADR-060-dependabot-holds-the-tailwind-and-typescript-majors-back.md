# ADR-060: Dependabot holds the Tailwind CSS and TypeScript majors back

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** the `/frontend` `npm` entry of `.github/dependabot.yml`, for `tailwindcss` and
  `typescript`. NFR-53 in `docs/requirements/requirements.md`.
- **Issue:** [#337](https://github.com/NaimElijah/UpHealther/issues/337), scan 03 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding D3.F2. The migrations this
  decision defers are [#369](https://github.com/NaimElijah/UpHealther/issues/369), Tailwind CSS 4, and
  [#370](https://github.com/NaimElijah/UpHealther/issues/370), TypeScript 6 and then 7. It extends
  [ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md) and follows
  [ADR-048](ADR-048-dependabot-holds-react-majors-back.md), which does the same for React.

## Context

On 2026-10-20 Dependabot opened two pull requests for the `/frontend` `npm` entry, and both are red:

- [#319](https://github.com/NaimElijah/UpHealther/pull/319) moves `tailwindcss` from 3.4.19 to 4.3.3.
  The build fails: "It looks like you're trying to use `tailwindcss` directly as a PostCSS plugin".
  Tailwind 4 moved its PostCSS plugin into a separate package.
- [#320](https://github.com/NaimElijah/UpHealther/pull/320) moves `typescript` from 5.9.3 to 7.0.2.
  `npm ci` fails with `ERESOLVE`: the newest `typescript-eslint`, 8.72.1, declares a peer of
  `typescript >=4.8.4 <6.1.0`.

The entry holds two open PRs at a time (ADR-046), so these two hold both slots. Dependabot's last
grouped minor and patch PR for the frontend is [#311](https://github.com/NaimElijah/UpHealther/pull/311),
on 2026-10-19. Since then `npm outdated` shows newer releases waiting for `axios`, `vite`, `postcss`,
`@tanstack/react-query` and `typescript-eslint`, and no PR proposes them.

Three things follow:

- **Neither major can merge as proposed.** Each needs a change of its own: Tailwind 4 changes how the
  stylesheet and the PostCSS pipeline are configured, and TypeScript 7 waits on `typescript-eslint`.
- **Each is a migration, not a bump.** Neither has been chosen against the version the frontend runs
  on. They are the same kind of decision as a React major (ADR-048).
- **The repository already says how such updates are treated.** `dependabot.yml`'s header lists
  framework and toolchain majors as "ignored outright, and moved by hand". ADR-046 holds
  `react-router-dom` majors because "a declined 7.x PR would otherwise hold an open-PR slot".

Tailwind 3 has a further cost. Its latest release, 3.4.19, is dated 2025-12-10, ten months before
this record, so a fix on the 3.x line cannot be counted on. Dependabot alert #8 on `postcss-selector-parser`, tracked in
[#302](https://github.com/NaimElijah/UpHealther/issues/302), is fixed in 7.1.6, which is outside
Tailwind 3's range of `^6.1.2`.

## Decision

**Dependabot's `/frontend` `npm` entry ignores semver-major updates of `tailwindcss` and
`typescript`.**

- Their minor and patch updates still arrive, in the weekly `npm-minor-and-patch` group.
- Each major is a hand-made change under its own issue. #369 moves Tailwind to 4. #370 moves
  TypeScript to 6 first, which `typescript-eslint`'s range admits, and to 7 once `typescript-eslint`
  accepts it. A migration lifts its hold through an ADR that supersedes this one for that package.
- #319 and #320 are closed with a pointer here once this change reaches `main`, because Dependabot
  reads its configuration from there (ADR-046).

## Consequences

**What this makes easy**

- **Both of the `npm` entry's PR slots are free again** for updates that can merge. The waiting
  `axios`, `vite`, `postcss`, `@tanstack/react-query` and `typescript-eslint` releases are proposed
  in the next weekly run after the change reaches `main`.
- **The reason is in the tree.** `dependabot.yml` carries the ignore, and this record says why, as
  ADR-048 does for React.

**What this makes hard**

- **Nothing proposes Tailwind 4 or TypeScript 6 or 7 any more.** #369 and #370 are the only things
  that keep them in view.
- **The frontend stays on Tailwind 3 for as long as #369 waits.** The 3.x line has had no release
  since 3.4.19, so an advisory in Tailwind 3 may get no fix on that line. The `postcss-selector-parser` alert #8 is in this
  position today, and it is fixed only by leaving Tailwind 3. Its severity is medium, and #302 holds
  the decision.
- **The hold works only once it reaches `main`.** Until a release promotes this change, a weekly run
  can reopen a major PR for either package. Such a PR is closed with a pointer here.
- **A future major of either is held the same way.** This rule names packages, not versions.

## Alternatives considered

- **Leave #319 and #320 open.** Rejected: the stall continues. Both are red for reasons no Dependabot
  rebase fixes, and each holds a slot until someone migrates.
- **Close them with `@dependabot ignore this major version`.** Rejected. It holds the major but
  leaves no trace in the tree, the reason ADR-046 puts React Router's hold in the file.
- **Raise `open-pull-requests-limit`.** Rejected. It lets the minor and patch PRs through again, but
  hides the stall and records no decision. The two red PRs would sit there, and a third major would
  fill the extra slot the same way.
- **Migrate now.** Rejected here, not on its merits. Each is a migration of its own, with application
  code and review, and outside the scope of a dependency scan. #369 and #370 track them.

## When to revisit

- **#369 or #370 is taken on.** The hold on that package is lifted in the same change.
- **`typescript-eslint`'s peer range accepts TypeScript 7.**
- **An advisory against Tailwind 3 has no fix on the 3.x line.**
- **The design work ([#230](https://github.com/NaimElijah/UpHealther/issues/230)) wants Tailwind 4's
  `@theme`.**
