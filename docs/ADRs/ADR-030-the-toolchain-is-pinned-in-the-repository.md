# ADR-030: The toolchain is pinned in the repository

- **Status:** Accepted — extended by
  [ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md), which turns on the Dependabot
  updates its "Lost: security patches arriving on their own" waited for; by
  [ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md), whose `e2e/`
  package takes the frontend's `engines` range with an `.npmrc` of its own setting `engine-strict`; and
  by [ADR-055](ADR-055-python-is-left-floating-at-any-python-3.md), which adds Python, run by the diagram
  check and by CI's backend coverage summary, to the "Deliberately left floating" list below
- **Date:** 2026-10-13
- **Scope:** `backend/mvnw`, `backend/.mvn/`, `backend/pom.xml` (maven-enforcer-plugin), `frontend/.nvmrc`,
  `frontend/.npmrc`, the `engines` field of `frontend/package.json`, both Dockerfiles,
  `docker-compose.yml`, the integration-test PostgreSQL image, and `.github/workflows/ci.yml`. NFR-51 in
  `docs/requirements/requirements.md`.
- **Issue:** [#165](https://github.com/NaimElijah/UpHealther/issues/165)

## Context

Nothing in the repository decided which tools built it.

- **Maven** was whatever the machine had: 3.9.6 on the maintainer's, the version `ubuntu-latest`
  ships in CI, and whatever `apk add maven` installed in the backend image on the day of the build.
  `README.md` asked for "Maven 3.8+".
- **Java** was pinned by `java.version` in the POM, which only sets the compiler's `--release`. A
  JDK 17 failed with a compiler error that did not name the problem. A JDK 25 compiled for 21, then
  ran the tests on 25, while the image ships a JRE 21.
- **Node** was named in three places, three ways: `node-version: '20'` in CI, `node:20-alpine` in
  the image, and "Node 20+" in the README. A developer could run a different one. ADR-004 records this
  biting once. jsdom 30 installed and passed on a newer local Node, then failed only on CI's Node 20,
  with an error that named nothing in this project. npm had warned (`EBADENGINE`) and carried on.
- **Base images floated.** `nginx:alpine` floats over every nginx major. `node:20-alpine`,
  `eclipse-temurin:21-*-alpine` and `postgres:15-alpine` float over every patch and Alpine release.
  The shipped artifact could change with no commit. The integration tests and compose each named
  `postgres:15-alpine`, and only a comment in `PostgresIT` kept the two equal.

So the same commit could build three ways, and nothing recorded which.

## Decision

**Every tool that builds or runs the system is recorded once, in the repository. CI and the images
build on an exact version. A developer machine must sit inside a recorded range. A build outside what
is recorded fails before it does any work.**

- **Maven: the Maven wrapper, pinned to 3.9.16.**
  - `backend/mvnw` (with `mvnw.cmd` for Windows) downloads that exact distribution and runs it.
  - It is the `only-script` wrapper type, so there is no `maven-wrapper.jar` to commit or trust.
  - `distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties` makes the script verify the
    download before running it. The value was computed from the distribution, whose SHA-512 matched
    the one Maven Central publishes.
  - CI, the backend image and every documented command call `./mvnw`.
- **The enforcer refuses a toolchain outside the pin.** `maven-enforcer-plugin` runs at `validate`:
  - `requireJavaVersion [21,22)` refuses any JDK but 21, including a newer one. The image runs a JRE 21,
    so testing on 25 would test something that is not shipped.
  - `requireMavenVersion [3.9.16,4)` refuses an older Maven, with a message that says to use
    `./mvnw`. A newer 3.x passes. The wrapper, not the enforcer, is what makes CI and the image build
    with 3.9.16.
  - The plugin version is the one `spring-boot-starter-parent` manages, so this is a declaration, not
    a new artifact.
- **Node: `frontend/.nvmrc` names the exact release that CI and the image build with, and npm refuses
  a Node outside the `engines` range.**
  - CI reads `.nvmrc` with `node-version-file`. The image's tag names the same release, and
    `ContainerImagesArePinnedTest` fails if the two differ.
  - `package.json` declares an `engines` range. `frontend/.npmrc` sets `engine-strict=true`, so npm
    refuses to install on a Node outside it. Without that, npm only warns, and that warning is what
    ADR-004 records being installed past.
  - The range is wider than `.nvmrc` on purpose: it admits the Node line that `.nvmrc` pins, from the
    oldest patch a maintainer runs. `.nvmrc` is what CI and the image build with. The range is what a
    developer machine must be inside. Which Node line is [ADR-031](ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md).
- **Every container image names an exact version, down to the patch and the Alpine release.** That
  covers `eclipse-temurin` (one release for both the JDK and the JRE stage), `node`, `nginx`, and
  `postgres` in both compose and the integration tests.
  - Images are tagged, not pinned by digest.
  - `ContainerImagesArePinnedTest` fails the build on any floating tag. It also fails if compose and
    the integration tests name different PostgreSQL images, or the frontend image a Node other than
    `.nvmrc`'s.
  - The integration tests read their tag from `support/PostgresImage`, which has no side effects. That
    lets the test compare it without starting Docker.
- **Deliberately left floating:**
  - the JDK patch that CI's `setup-java` resolves for `java-version: '21'` (the enforcer still holds it
    to 21);
  - the `ubuntu-latest` runner;
  - the `actions/*@v4` major tags.

  None of them is in a shipped artifact today, because nothing ships from CI.

## Consequences

- **A build is reproducible from the commit where it ships from.** CI and the images build on exactly
  the recorded Maven, Node and image versions. A developer machine runs the wrapper's Maven, Java 21, and
  a Node inside the `engines` range.
- **A wrong toolchain fails at the start, by name.** A wrong JDK, an old Maven or a wrong Node is
  refused before anything compiles or installs, with a message that names what is wrong.
- **Moving a version is a deliberate, reviewable change in a known place.** Maven is
  `maven-wrapper.properties` and the enforcer's lower bound. Node is `.nvmrc`, `engines` and the image
  tag. Each image is its tag.
- **Lost: security patches arriving on their own.** A floating tag such as `nginx:alpine` picked up a
  patched release on every rebuild. A pinned tag never does, so someone has to move it. Until
  [#164](https://github.com/NaimElijah/UpHealther/issues/164) turns on Dependabot's `docker`, `npm`
  and `maven` updates, nothing proposes the bump. That makes #164 the counterpart of this decision,
  not an optional extra.
- **A developer must use the wrapper.** A plain `mvn` older than the pinned version is refused. On
  Windows the command is `mvnw.cmd`, or `./mvnw` from Git Bash.
- **The first wrapper run downloads Maven** (about 9 MB) into `~/.m2/wrapper`. CI's Maven cache does
  not necessarily keep it, so a CI run may download it again. That costs a few seconds and needs no
  cache step of its own.
- `.gitattributes` gives `*.cmd` CRLF line endings. `mvnw.cmd` is the first tracked file that needs
  them, and `mvnw` keeps LF and its executable bit.

## Alternatives considered

- **Pinning npm with `packageManager` and Corepack.** Rejected. The lockfile already pins every
  package, and the npm that ships with the pinned Node is fixed by that Node.
  - **Revisit** if two npm versions are seen writing different lockfiles.
- **Requiring a toolchain manager** (`asdf`, `mise`, SDKMAN). Rejected. The wrapper, the enforcer and
  `.nvmrc` work with whatever installer a developer already uses, and `nvm`, `fnm` and `volta` all read
  `.nvmrc`.
  - **Revisit** if a third runtime joins the build. One `.tool-versions` would then be simpler than
    three mechanisms.
- **Pinning images by digest** (`image:tag@sha256:…`). Rejected for now. A digest buys byte-for-byte
  reproducibility, but it never moves on its own and cannot be read at a glance. Without an updater it
  is a slower way to the same staleness as an exact tag.
  - **Revisit** when #164's Dependabot is updating image tags. It can then keep digests current too.
- **Tagging images at `major.minor` only** (`nginx:1.30-alpine`). Rejected. It picks up patches, but
  the shipped artifact still changes without a commit, which is the failure this record exists to
  end.
  - **Revisit** if #164's Dependabot cannot keep exact tags current, for example if an image's
    upstream publishes patch releases faster than the bumps are reviewed.
- **A `maven-wrapper.jar` wrapper type.** Rejected. It puts a binary in the repository, and the
  `only-script` type needs nothing but `wget` or `curl`, which every build environment here has. The
  backend's build image provides `wget`, `unzip` and `sha256sum`.
  - **Revisit** if a build environment has neither `wget` nor `curl`.
- **`requireJavaVersion [21,)`**, which accepts any newer JDK. Rejected. It is exactly the "tests run
  on 25, the image ships 21" case.
  - **Revisit** when the runtime image moves to a newer Java, together with `java.version`.

## When to revisit

- **#164 lands.** Image tags, Node and Maven then move by Dependabot PRs, and digest pinning becomes
  cheap (see above).
- **Maven 4 is released as stable.** Raise the wrapper and the enforcer's range together.
- **CI starts publishing the images** ([#154](https://github.com/NaimElijah/UpHealther/issues/154)).
  CI's runner, its JDK patch and its actions then shape what ships, so pin the actions by commit SHA
  and let Dependabot move them.
- **A tool is added to the build** (a formatter in #161, a linter in #162). It is pinned the same way,
  in the same commit that adds it.
