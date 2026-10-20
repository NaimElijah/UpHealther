# ADR-050: One version names the whole repository, and a test holds every field to it

- **Status:** Accepted
- **Date:** 2026-10-21
- **Scope:** the `<version>` in `backend/pom.xml`, the `version` in `frontend/package.json` and both
  of its lockfile's, `RepositoryVersionContractTest` and `support/RepositoryVersion`. NFR-54 in
  `docs/requirements/requirements.md`.
- **Issue:** [#153](https://github.com/NaimElijah/UpHealther/issues/153), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197).

## Context

- **Nothing could say what is running.** Thirty-eight pull requests had promoted `dev` to `main`, and
  none of them named what it shipped. A deployment, a bug report and a rollback each need a name for
  "the code as of that promotion". The only one was a merge-commit SHA.
- **The two version fields disagreed, and nothing read either.** `backend/pom.xml` said
  `0.0.1-SNAPSHOT`. `frontend/package.json` and its lockfile said `1.0.0`.
- **Work already waits on a version.** #154 tags the images it publishes with it, and #215 deploys by
  it.

## Decision

**One version, in Semantic Versioning, names the API and the interface together.** They ship together
and are never released apart, so two numbers would only be two things to keep in step. It starts at
`0.1.0`. A `0.x` version says what is true: nothing is promised to an outside consumer, because there
is no public API (requirements §5.4).

**Four fields carry it:**

- the `<version>` in `backend/pom.xml`, with no `-SNAPSHOT`;
- the `version` in `frontend/package.json`;
- the two its lockfile repeats, `version` and `packages[""].version`.

A release sets them with two commands, which run the same from PowerShell, Git Bash and a Linux
shell:

- `cd frontend && npm version X.Y.Z --no-git-tag-version` sets the manifest and the lockfile;
- `node scripts/mvnw.mjs versions:set "-DnewVersion=X.Y.Z" -DgenerateBackupPoms=false` sets the
  pom.
  - It uses versions-maven-plugin 2.21.0, the version the Spring Boot parent manages, so the pom
    declares no plugin for it.
  - The quotes are for PowerShell 5.1. Without them it splits `-DnewVersion=0.1.0` at the first dot.

**`RepositoryVersionContractTest` holds the four fields together.** It fails the build when a field
differs from the pom's, and when the pom's is not plain `MAJOR.MINOR.PATCH`.

- **Where it runs.** It is a unit test beside the other contract tests that read files outside
  `backend/` (`FrontendEnumContractTest`, `ContainerImagesArePinnedTest`), so `./mvnw test` runs it.
  CI's backend job gates it with no change to `ci.yml` or to ruleset 25310599.
- **How it reads the pom.** `support/RepositoryVersion` reads the pom as a document, not as text,
  because the parent's `<version>` comes first in the file.
- **It reads files and never asks git.** CI's checkout is shallow and has no tags, and a release's tag
  exists only after its promotion has merged.

## Consequences

- **A release that bumps one module and forgets the other fails on its own pull request**, not in a
  deployment.
- **Between releases, `dev` carries the last release's version.** A build from `dev` is named after the
  release it follows, not after the one it will become. Nothing deploys from `dev`: #198 is
  production-only, and a release is what it deploys.
- **A pre-release or build suffix such as `0.2.0-rc.1` is refused.** Nothing needs one.
- **The frontend's version is not shown in the interface.** The backend's is what an operator asks
  for, and it is the same number.

## Alternatives considered

- **A Python step in `ci.yml`**, as #153 proposed. Rejected. NFR-52 says CI's jobs run the root
  scripts and nothing else that gates, so a check that ran only in CI would be missing from `npm test`.
- **A `scripts/*.mjs` check called from a root `test:*` script.** Rejected. It would work, but the
  repository's other cross-module contracts are backend tests, and a requirement names a test.
- **Separate versions for the backend and the frontend.** Rejected while they ship as one.
  - **Revisit when** they are deployed on different cadences.
- **A `-SNAPSHOT` suffix on `dev` between releases**, Maven's convention. Rejected. Every release would
  then be two version bumps instead of one, for a distinction nothing reads.
  - **Revisit when** builds from `dev` are deployed anywhere.

## When to revisit

- **The backend and the frontend are deployed separately:** give each its own version.
- **A release candidate needs a name of its own**, for example to test it in an environment before
  production: let the test accept a pre-release suffix.
- **Builds from `dev` are deployed:** give them a version that says so, such as a suffix or the commit.
