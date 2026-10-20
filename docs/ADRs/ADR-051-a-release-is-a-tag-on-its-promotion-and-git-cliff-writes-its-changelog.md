# ADR-051: A release is an annotated tag on its promotion merge, and git-cliff writes its changelog

- **Status:** Accepted
- **Date:** 2026-10-21
- **Scope:**
  - `cliff.toml`, `CHANGELOG.md` and `scripts/compose.changelog.yml`;
  - the root `package.json`'s `changelog`;
  - the changelog checks in `RepositoryVersionContractTest`;
  - `.github/dependabot.yml`'s `docker-compose` entry;
  - the README's Releasing section;
  - NFR-54 in `docs/requirements/requirements.md`.
- **Issue:** [#153](https://github.com/NaimElijah/UpHealther/issues/153), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It names releases by the
  version [ADR-050](ADR-050-one-version-names-the-whole-repository.md) gives the repository, and it
  extends [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md) with a root script.

## Context

- **The release unit already exists.** Every promotion is a pull request from `dev` into `main`,
  merged as a merge commit. That merge is "the code as of that promotion", and it had no name: there
  was no tag, no release page and no changelog.
- **The changelog's input already exists.** Of the 505 non-merge commits, 497 follow Conventional
  Commits. The 8 that do not are the earliest.
- **Both branches take changes only by pull request.** Ruleset 25310599 refuses a direct push to `dev`
  or `main`, so a release's version bump reaches `dev` the way every other change does.
- **The root `package.json` has no dependencies and no lockfile** (ADR-044), so a tool distributed
  through npm cannot be a dependency there.

## Decision

### A release is a tag on the promotion merge

**A promotion that ships a new version is tagged `v<version>` on its merge commit on `main`.** The tag
is annotated, and the first one is `v0.1.0`. It is cut by hand once the promotion has merged:

- `git tag -a vX.Y.Z origin/main -m "UpHealther X.Y.Z"`
- `git push origin vX.Y.Z`

A promotion every few days, by one person, is a short checklist.

**The tag is never in `dev`'s history**, because `main`'s merge commits do not come back into `dev`.
That does not matter to the changelog:

- git-cliff's `--unreleased` takes the range `<newest tag>..HEAD`;
- git's range excludes everything the tag reaches, which is all of `dev` up to that promotion;
- so the next section holds exactly the commits since the last release.

This was checked on a scratch repository with the same shape before it was written down.

**A tag push starts nothing.** `ci.yml` runs on branches only. #154 adds the workflow a tag will
start.

### git-cliff writes `CHANGELOG.md`

**New development dependency.** It never ships, and the image is used only to write the changelog.

| Dependency | Replaces | Licence | Maintenance |
|---|---|---|---|
| git-cliff 2.14.2, run as the image `orhunp/git-cliff:2.14.2` | A changelog written by hand, or none | MIT OR Apache-2.0 | Active; 2.14.2, released 2026-09-18, is the latest |

**It runs from its image, through `scripts/compose.changelog.yml`.** The command is
`npm run changelog -- --tag vX.Y.Z`, and the script adds `--unreleased --prepend CHANGELOG.md`. It is
handled like OSV-Scanner ([ADR-047](ADR-047-the-backend-audits-its-dependencies-with-osv-scanner.md)):

- **The pin.** The image is pinned to an exact version, `ContainerImagesArePinnedTest` holds it there,
  and Dependabot's `docker-compose` entry moves it.
- **The mount.** The container mounts the repository read-write, because it writes the file, and it
  has no network.
- **Ownership.** The image runs git-cliff as the owner of the mount, and it treats every directory as
  a safe git directory. So neither file ownership nor git's `safe.directory` check needs configuring.

**`cliff.toml` decides the shape:**

- **Sections.** There is one section per release, `## [x.y.z] - date`, with its commits grouped by
  type in a fixed order.
- **Entries.** Each commit is one line with its scope, its subject and its short id. A breaking change
  is marked.
- **What is left out:**
  - GitHub's merge commits and the 8 early commits are dropped as unconventional;
  - `chore(release)` commits are skipped, because they are the release itself;
  - git-cliff also drops, by a rule of its own, every commit `.git-blame-ignore-revs` lists and every
    commit that only edits that file. Those are the formatter runs, which change no behaviour.
- **A commit type no parser names fails the run** (`fail_on_unmatched_commit`) rather than vanishing.
- **The section's date** is the day it was generated, in UTC.

**The section is written in the version PR and committed with the bump.** So the changelog that
reaches `main` describes exactly what that promotion ships, and it is reviewed with it.

**The first section, `0.1.0`, holds the whole history**, because there was no earlier tag to start
from. It was written once with `--tag v0.1.0 --output CHANGELOG.md`, since `--prepend` needs a file
to prepend to.

### The release steps

The README's Releasing section lists them:

1. Fetch the tags.
2. Branch `chore/release-X.Y.Z` from `dev`.
3. Bump the version with ADR-050's two commands.
4. Run `npm run changelog -- --tag vX.Y.Z`.
5. Commit `chore(release): X.Y.Z` and merge that version PR into `dev`.
6. Promote `dev` to `main` with the `release` label.
7. Tag the promotion merge and push the tag.

**Nothing else merges into `dev` between steps 5 and 6.** A commit merged in between would ship in
that promotion without appearing in its section. It would then appear in no section at all, because
the next release's range starts at this tag.

### What fails when a step is skipped

`RepositoryVersionContractTest` checks the file's shape:

| Check | Catches |
|---|---|
| The newest section is the pom's version | A bump with no section written for it; a run without `--tag`, whose heading is "Unreleased" |
| No release appears twice | `--prepend` run twice |
| No commit appears twice | A run without the tags fetched, which writes the whole history again under the new version |

The tag itself is not checked by a test. It exists only after the promotion merges, and CI's checkout
has no tags. The check is `git describe --exact-match origin/main`, after `git fetch origin --tags`.

## Consequences

- **A release is one version PR, one promotion PR and one tag push.** No tool owns the flow, and the
  flow is the one that already existed.
- **The changelog is only as good as the commit subjects.** A subject written for a reviewer is what a
  reader of the changelog sees.
- **Writing a changelog needs Docker**, as `npm test` already does.
- **The file is only ever prepended to.** Regenerating it whole from `dev` puts every release under one
  heading, because the tags sit on `main`'s merge commits, outside `dev`'s history. This was seen on the
  scratch repository.
- **The header is fixed text.** `--prepend` drops the old header by matching it exactly, so changing
  `cliff.toml`'s header means changing the one in `CHANGELOG.md` in the same commit.
- **It cannot run from a git worktree.** A worktree's `.git` is a file pointing at a path outside the
  mount.
- **The `0.1.0` section is long**, about 500 entries, because it is the whole history.

## Alternatives considered

- **release-please.** Rejected. Its documented model is release pull requests of its own, which it
  opens and keeps up to date. This repository already has a release pull request: the promotion from
  `dev` to `main`. Whether release-please could drive that one instead of adding a second per release
  was not checked, because the plain tool fits the existing flow without it.
  - **Revisit when** releases are frequent enough that the version PR is worth automating.
- **A script of our own over `git log <tag>..HEAD --no-merges`.** Rejected. Breaking changes, scopes,
  ordering and every edge case above would be ours to write and keep working; git-cliff has them.
- **git-cliff as a dependency of the root `package.json`.** Rejected. ADR-044 keeps the root free of
  dependencies and of a lockfile.
- **git-cliff through `npx git-cliff@2.14.2`.** Rejected. Its pin would sit in a script string no
  updater reads, with no lockfile holding its integrity. The image is pinned where Dependabot keeps it
  current and a test checks it.
- **The binary, downloaded by a `scripts/*.mjs`.** Rejected for the reason ADR-047 gives: platform
  checksums kept by hand.
- **Bumping and tagging automatically on merge.** Rejected.
  - **Revisit when** a release is missed or mis-tagged because the checklist was skipped.
- **Tagging the 38 earlier promotions.** Rejected. They shipped unnamed, and naming them now buys a
  longer changelog nobody reads.
- **A GitHub Release page for each tag.** Not done. The tag and `CHANGELOG.md` are the record, and
  `gh release create vX.Y.Z --notes-file <section>` adds a page if anybody wants one.

## When to revisit

- **A release is missed or mis-tagged:** automate the bump and the tag.
- **#154 publishes images when a `v*` tag is pushed:** the tag becomes a trigger, so decide there
  whether a mistaken tag may be deleted and pushed again.
- **The changelog should link each commit:** add a link to the repository's commit page in
  `cliff.toml`'s template. It needs no network.
