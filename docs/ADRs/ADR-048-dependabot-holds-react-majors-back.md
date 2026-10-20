# ADR-048: Dependabot holds React majors back, as it does Spring Boot's

- **Status:** Accepted
- **Date:** 2026-10-20
- **Scope:** the `npm` entry of `.github/dependabot.yml`, for `react`, `react-dom`, `@types/react` and
  `@types/react-dom`. NFR-53 in `docs/requirements/requirements.md`.
- **Issue:** [#314](https://github.com/NaimElijah/UpHealther/issues/314), the migration this decision
  defers. It extends [ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md) by adding to the
  updates it holds back.

## Context

Dependabot's first `npm` run, on 2026-10-19, opened
[#312](https://github.com/NaimElijah/UpHealther/pull/312):

- It moved `react-dom` from 18.3.1 to 19.3.1, and `@types/react-dom` to 19.3.0, as two dependencies
  that "needed to be updated together".
- It left `react` and `@types/react` on 18.
- `npm ci` refused the result with `ERESOLVE`, so the frontend and image jobs failed:
  - `react-dom` 19.3.1 declares a peer of `react` `^19.3.1`;
  - `@types/react-dom` 19.3.0 declares a peer of `@types/react` `^19.3.0`.

Three things follow from it:

- **The four packages install only together.** Each peer range names the other's major. A PR that
  moves some of them cannot be merged as it stands.
- **Even moved together, React 19 is a migration, not a routine bump.**
  - Every component and test runs on it.
  - Its types are stricter than 18's.
  - ADR-046 already treats a Spring Boot major this way: "one moves Spring, Security, Hibernate and
    Jackson at once".
  - No ADR has chosen between React 18 and 19. The frontend has run on 18 since it was written.
- **The `npm` entry may hold two open PRs (ADR-046).** A React PR left open until someone takes on the
  migration fills one of them. ADR-046 gives this as the reason for ignoring React Router majors
  ([ADR-034](ADR-034-stay-on-react-router-6-while-its-open-advisories-do-not-reach-the-spa.md)).

## Decision

**Dependabot's `npm` entry ignores semver-major updates of `react`, `react-dom`, `@types/react` and
`@types/react-dom`.**

- Their minor and patch updates still arrive, in the weekly `npm-minor-and-patch` group.
- The move to React 19 is a hand-made change, tracked as #314. It moves all four packages in one
  commit, and lifts this hold through an ADR that supersedes this one.
- #312 is closed, not merged.

## Consequences

- **Both of the `npm` entry's PR slots stay free** for updates that can merge.
- **Nothing proposes React 19 any more.** #314 is the only thing that keeps it in view.
- **The hold works only once it reaches `main`.** Dependabot reads its configuration from the default
  branch (ADR-046). Until a release promotes this change, a weekly run can still open a React major PR.
  Such a PR is closed with a pointer here.
- **A future React major is held the same way.** This rule names packages, not versions.

## Alternatives considered

- **Group the four packages, so that their majors arrive as one PR.** Rejected.
  - The grouped PR would install, but it would most likely go red on the stricter types.
  - It would then wait, holding one of the `npm` entry's two slots, until someone took on the
    migration. ADR-046 refuses to spend a slot that way on React Router.
  - **Revisit when** #314 has landed. A group then lets the next major arrive installable, if
    holding it is no longer wanted.
- **Migrate to React 19 now.** Rejected here, not on its merits. It changes application code and
  deserves its own review, rather than a corner of a pass that clears Dependabot's first PRs. #314
  tracks it.
- **Close #312 and do nothing else, or answer it with `@dependabot ignore this major version`.**
  Rejected.
  - Closing it stops Dependabot proposing those versions only. A later 19.x, or `react` on its own,
    would still be proposed, and broken in the same way.
  - The command would hold the major, but it leaves no trace in the tree. That is why ADR-046 puts
    React Router's hold in the file.

## When to revisit

- **#314 is taken on.** The hold is lifted in the same change.
- **An advisory against React 18 reaches the SPA, and is fixed only in 19.**
- **A dependency the frontend needs drops React 18** from its peer range.
