# ADR-046: Dependabot proposes weekly updates into `dev`; alerts, secret scanning and push protection are on

- **Status:** Accepted
- **Date:** 2026-10-19
- **Scope:** `.github/dependabot.yml`, and the repository's code-security settings, which live outside
  the tree. NFR-53 in `docs/requirements/requirements.md`.
- **Issue:** [#164](https://github.com/NaimElijah/UpHealther/issues/164), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It extends
  [ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md), whose pinned versions it keeps
  moving.

## Context

Every security feature GitHub gives a public repository for free was off:

- Dependabot alerts;
- Dependabot security updates;
- secret scanning;
- push protection.

No `.github/dependabot.yml` existed either, so nothing proposed a dependency update.

ADR-030 pinned every toolchain and image to an exact version, and named the cost: "Lost: security
patches arriving on their own." It made #164 "the counterpart of this decision, not an optional
extra". Until now, a version moved only when someone remembered. The Spring Boot 3.2 line had left
even extended support before #73 moved it.

The repository's flow constrains how updates can arrive:

- **Work merges into `dev`, and `dev` reaches `main` only by a release PR.** That PR is a merge commit,
  never a squash. Ruleset 18678702 is bypassed for this (ADR-043).
- **Every PR into either branch must pass the four CI jobs.** Ruleset 25310599 has no bypass.
- **`main` is the default branch.**

## Decision

### Repository settings

These are switched on outside the tree, and read back with `gh api`:

- **Dependabot alerts.**
  - `gh api repos/NaimElijah/UpHealther/vulnerability-alerts` answers 204.
  - They are read with `gh api repos/NaimElijah/UpHealther/dependabot/alerts`.
  - They cover only what GitHub's dependency graph sees:
    - every package in `frontend/package-lock.json`;
    - in `backend/pom.xml`, only the artifacts that state their own version, such as jjwt and
      logstash-logback-encoder.

    The graph reads the pom without resolving it. It records the parent-managed starters with no
    version and no transitive dependency at all, so Tomcat, Spring, Jackson and Hibernate raise no
    alert. Automatic dependency submission would resolve the tree. It is a switch in the settings UI,
    with no REST endpoint, so it is left to the owner as
    [#301](https://github.com/NaimElijah/UpHealther/issues/301).
- **Secret scanning and push protection.**
  - `gh api repos/NaimElijah/UpHealther --jq .security_and_analysis` shows both `enabled`.
  - Push protection refuses a push that contains a token in a format GitHub recognises, such as a
    cloud key or a GitHub token.
  - It does not flag the published development signing key in `backend/config/application.yml` and
    `docker-compose.yml`. The key matches no provider pattern, and it is published on purpose
    ([ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md), #285).

**Dependabot security updates stay off.** GitHub's options reference is explicit on two points:

- once an entry sets `target-branch`, its options "no longer apply to security updates";
- security updates "always use the default branch".

Here the default branch is `main`. Each security fix would then land on `main` without passing
through `dev`, and would have to be merged back. Until it was, `main` would hold commits `dev`
lacks, which is the divergence the merge-commit-only promotion exists to prevent.

So a known vulnerability raises an alert, and how its fix arrives depends on where the package sits:

- **A dependency a manifest declares** is moved by the next weekly version update into `dev`, or by
  hand sooner.
- **A transitive dependency is moved only by hand.** Version updates keep current "all dependencies
  explicitly defined in a manifest", and for npm the options reference supports no `indirect`
  dependency type. Lock-file-only packages are what security updates would have moved. On `dev`, the
  fix is `npm update <package>` inside the existing ranges, or an `overrides` entry when the fix lies
  outside them.

### `.github/dependabot.yml`: version updates

| Ecosystem | Directories | Ignored, as a decision rather than a routine bump |
|---|---|---|
| `maven` | `/backend` | `spring-boot-starter-parent` majors: one moves Spring, Security, Hibernate and Jackson at once ([ADR-032](ADR-032-the-backend-runs-on-spring-boot-4-1.md)); `org.apache.maven:apache-maven` majors, for the wrapper: the enforcer refuses Maven 4 (`requireMavenVersion [3.9.16,4)`, [ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md)) |
| `npm` | `/frontend` | `prettier`, every update: a formatter bump is a `style(frontend)` commit of its own, whose SHA goes into `.git-blame-ignore-revs` ([ADR-038](ADR-038-the-frontend-is-formatted-by-prettier.md)), not a line in a grouped `build(deps-dev)` PR; `react-router-dom` majors: [ADR-034](ADR-034-stay-on-react-router-6-while-its-open-advisories-do-not-reach-the-spa.md) holds it on 6 until its own triggers fire, and a declined 7.x PR would otherwise hold an open-PR slot, or be ignored through a `@dependabot` command that leaves no trace in the tree |
| `github-actions` | `/` | none |
| `docker` | `/backend`, `/frontend` | `eclipse-temurin` and `node` majors: a runtime change ([ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md), [ADR-031](ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md)) |
| `docker-compose` | `/` | `postgres` majors: a major will not start on the existing data volume, so it is a data migration |

Every entry:

- **runs weekly**;
- **targets `dev`**, like every other change;
- **groups minor and patch updates into one PR per ecosystem.** Any update outside the group, which
  means a major, gets a PR of its own and is reviewed as the decision it is;
- **is limited to two open PRs**, to hold the first runs to a reviewable burst;
- **writes a Conventional Commit**: `build(deps): …`, `build(deps-dev): …`, and `ci(deps): …` for
  actions;
- **adds no labels** (`labels: [ ]`). The repository's four-family taxonomy is for issues, and
  Dependabot's default labels would add `dependencies`, `java` and `docker` beside it.

Dependabot reads this file from the default branch only. The configuration takes effect when it is
promoted to `main`, not when it merges into `dev`.

## Consequences

- **A pinned version now has something that proposes its bump.** This meets the consequence ADR-030
  left open, with one limit:
  - **The Alpine release in an image tag never moves.** dependabot-core's `docker/tag.rb` reads
    `24.21.0-alpine3.24`, `1.30.5-alpine3.24` and `15.19-alpine3.24` as a version plus the suffix
    `-alpine3.24`. It proposes only candidates with an equal suffix.
  - So `node`, `nginx` and `postgres` get patches on Alpine 3.24 for as long as upstream publishes
    them there. After that the proposals stop, silently.
  - The `eclipse-temurin` tags carry the Alpine release inside the version
    (`21.0.12.1_1-jre-alpine-3.24`), and are unaffected.
  - Moving the Alpine release is a hand-made change. For `postgres`, it moves in
    `docker-compose.yml` and `PostgresImage` together.
- **Nothing reaches `main` that did not pass through `dev`.** A Dependabot PR is gated by the same four
  required checks as any other.
- **Two kinds of Dependabot PR go red by design, and a person finishes them.**

  | Bump | Fails on | Companion change |
  |---|---|---|
  | `node` image (`frontend/Dockerfile`) | `ContainerImagesArePinnedTest`'s `.nvmrc` check | Move `frontend/.nvmrc` with it |
  | `postgres` image (`docker-compose.yml`) | `ContainerImagesArePinnedTest`'s compose check | Move `support/PostgresImage.NAME`, which the integration tests run, with it |

  Dependabot cannot edit either companion: one is a plain-text file no ecosystem reads, the other a
  Java string. The fix is to push the companion change onto the PR's branch. Ignoring both images
  instead would bring back the lost patches ADR-030 named.
- **A merged update also moves the README's tech-stack table.** The table names about twenty exact
  versions (Spring Boot 4.1.1, Flyway 12.4.0, Vite 8.3.4 and others), and Dependabot edits none of them.
  Whoever finishes an update moves the row it changes, so the README keeps describing the code.
- **Alerts arrive before CI notices.** The first read, on 2026-10-19, listed six open alerts, all in
  `frontend/package-lock.json`:
  - **Four are development-scope**: `browserslist` (high), `baseline-browser-mapping`, and two in
    `postcss-selector-parser`. `npm audit --omit=dev` deliberately ignores them (ADR-044). All four are
    transitive, so no weekly run will move them. Three are fixed inside their ranges, and the
    fourth, `postcss-selector-parser` 7.1.6, lies outside Tailwind 3's. They are tracked as
    [#302](https://github.com/NaimElijah/UpHealther/issues/302).
  - **Two are `react-router` advisories** that [ADR-034](ADR-034-stay-on-react-router-6-while-its-open-advisories-do-not-reach-the-spa.md)
    already accepts, because neither reaches the SPA.
- **A fix for a declared dependency waits up to a week unless someone acts on its alert. A fix for a
  transitive one waits until someone does.** That is the cost of leaving security updates off.
- **The backend's managed and transitive tree raises no alert until #301 lands.** An advisory against
  Tomcat or Jackson is announced only by whatever else watches that tree.
- **The `github-actions` updates will propose new majors** of `checkout`, `setup-java`, `setup-node`
  and `upload-artifact`. That is the route out of #292's forced move from Node 20 to Node 24 on the
  runner.
- **Two revisit triggers have fired**, and are tracked as
  [#299](https://github.com/NaimElijah/UpHealther/issues/299) rather than done here:
  - ADR-030's digest pinning, now that something can keep a digest current;
  - ADR-043's commit-SHA pinning of actions.
- **Not verified until the first runs on `main`:**
  - whether Dependabot updates the Maven wrapper at all. dependabot-core has a wrapper updater for
    `org.apache.maven:apache-maven` behind a feature flag, and its rollout is not known. The entry
    ignores its majors in case it is live;
  - whether Dependabot parses every exact tag here, for example
    `eclipse-temurin:21.0.12.1_1-jre-alpine-3.24`;
  - whether its npm updater accepts `engine-strict` with `engines.node` at `^24.16.0`. Its image
    runs Node 24 today (`NODEJS_VERSION=24` in dependabot-core's `npm_and_yarn/Dockerfile`). The same
    file says to "Always use the Active LTS version", and Node 26 is due to become Active LTS in
    October 2026.
    - So expect ADR-031's trigger soon after this reaches `main`: the npm update fails on
      `engine-strict`.
    - Its response is ADR-031's to choose. One option is to relax `engine-strict` for that job, with a
      recorded reason. The other is to widen `engines` to admit Node 26, which is a toolchain decision
      of its own.
    - Whether npm enforces the root package's `engines` during Dependabot's lockfile update is itself
      unverified.
- **Push protection was verified by its setting only.** No real token was pushed to prove the refusal.

## Alternatives considered

- **Turn security updates on and accept their PRs into `main`.** Rejected. Fixes would arrive
  fastest, but each one would put commits on `main` that `dev` lacks until it was merged back.
  - **Revisit when** an alert that no ADR accepts sits unactioned past a weekly run, so the wait
    has actually cost something.
- **Make `dev` the default branch,** so security PRs land where everything else does. Rejected
  here. It changes what a visitor sees first and where every PR opens by default, which is a bigger
  decision than this one.
  - **Revisit together with** the security-update trigger above.
- **Renovate.** Rejected. It does all of this and more, including merge confidence and SHA pinning
  presets. But it is a third-party app with write access to the repository, where Dependabot is
  built into GitHub and needs no installation.
  - **Revisit when** Dependabot cannot express a rule this repository needs, for example updating
    `frontend/.nvmrc` with the `node` image.
- **No grouping, and Dependabot's default limit of five PRs per entry.** Rejected. Five entries could
  open 25 PRs at once, each running the full CI.
- **Secret scanning's non-provider patterns and validity checks.** Not turned on, because #164 asked
  for neither.
  - Non-provider patterns match generic secrets such as private keys and connection strings.
  - Validity checks ask a provider whether a found token is live.
  - **Revisit when** a public deployment makes a leaked generic secret costly, with #198.

## When to revisit

- **A pinned image stops receiving proposals** while its upstream has moved to a newer Alpine release:
  move the suffix by hand. That is ADR-030's "Dependabot cannot keep exact tags current" trigger, for
  the Alpine part of a tag.
- **An alert that no ADR accepts sits unactioned past a weekly run:** security updates, and the
  default branch (above). The four development-scope alerts of the first read count once #302 has
  had its week.
- **The first Dependabot runs fail on a tag or on `engine-strict`:** adjust the entry. If `node` cannot
  be updated without `.nvmrc`, consider Renovate.
- **The PR backlog from the first runs has cleared:** raise `open-pull-requests-limit`.
