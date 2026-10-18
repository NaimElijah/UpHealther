# ADR-045: CI builds both container images and starts the stack from them, as a fourth required check

- **Status:** Accepted
- **Date:** 2026-10-18
- **Scope:** `scripts/check-images.mjs`, `scripts/compose.images-check.yml`, the root `package.json`'s
  `test:images`, the `images` job in `.github/workflows/ci.yml`, ruleset 25310599, and
  `frontend/.dockerignore`. NFR-12 in `docs/requirements/requirements.md`.
- **Issue:** [#201](https://github.com/NaimElijah/UpHealther/issues/201), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It extends
  [ADR-043](ADR-043-ci-is-a-merge-gate.md) and
  [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md).

## Context

CI built the jar and the bundle, but never the images that ship them. Both Dockerfiles build from
source with their own toolchains, so a change that broke only an image passed every check:

- **Backend:** the Maven wrapper runs in an Alpine JDK image.
- **Frontend:** `npm ci` and the build run again in a Node image, and the result is copied into nginx.

Nothing in CI asked nginx to parse `nginx.conf`, and the `HEALTHCHECK`s ran only on developer
machines. Open issues are about to edit exactly these files: #213 hardens both images, and #208 and
#214 rewrite parts of `nginx.conf`. Today a typo there would be found at the first image publish
(#154), or as a production task that never becomes healthy.

## Decision

**`npm run test:images` builds both images and starts the stack from them**, through
`scripts/check-images.mjs`:

1. **It takes down anything an interrupted earlier run left behind.**
2. **It refuses to run while containers hold the stack's fixed names.** `docker-compose.yml` fixes the
   three `container_name`s and the network's subnet, and both are global across compose projects.
   Containers holding those names are almost always the developer's own stack, stopped but not
   removed, and the message says to run `docker compose down`. The names are read from the merged
   configuration, not copied.
   - The subnet is not checked. A network that holds it with no such containers is rare, for
     example one left by `docker compose rm`, or another project's network that the daemon happened
     to place there. It fails the check after the build, with Docker's "Pool overlaps" error, and the
     developer's own `docker compose up` fails on it the same way.
3. **It runs `docker compose build`, then `docker compose up --detach --wait --wait-timeout 240`.**
   `--wait` returns once every service reports healthy:
   - the backend's readiness probe has passed after Flyway migrated an empty database;
   - nginx answers, so it has loaded `nginx.conf`.
4. **On failure, it prints the stack's logs.**
5. **It always takes the stack down**, volumes and built images included. It exits with the first
   failure's status.

**It runs as its own compose project**, `uphealther-images-check`. Taking it down never touches a
developer's stack or its `postgres_data` volume.

**`scripts/compose.images-check.yml` publishes no host ports.** Compose's health-checks run inside the
containers, so the check needs none, and a published port would fail wherever something on the host
already holds 3000, 5432 or 8080. `ports: !reset []` drops the base file's lists; a plain `ports:`
would add to them. A browser suite that has to reach the stack (#167) publishes its one port there.

**CI runs it as a fourth job, `Images - Build & Start`, in parallel with the other three.** It is the
fourth required check of ruleset 25310599, added just before the PR that introduces the job merged, as
ADR-043 requires. The job needs no registry, no login and no secret: the workflow's
`contents: read` is enough.

**The job has `timeout-minutes: 20`.** ADR-043 left job timeouts out, to revisit if a job ever hangs.
This job is the one that can:
- both images download every dependency with no layer cache;
- `--wait-timeout` bounds only the start, not the build;
- a hung required check holds a PR for the default 360 minutes.

**`npm test` runs it last**, after the backend, because it is the slowest part. ADR-044's "the whole CI
gate" now includes it.

**`frontend/.dockerignore` keeps the host's build output out of the image context.** `COPY . .` would
otherwise lay a developer's `node_modules` over the container's, Windows-native binaries included,
and Vite would bake a local `.env*` into the bundle. A local check would then not build what CI's
clean checkout builds. The context went from 182.93 MB to 7.68 kB.

## Consequences

- **A Dockerfile, `nginx.conf` or compose change that breaks an image, or a service that never becomes
  healthy, fails the PR** rather than the first publish.
- **CI's wall-clock time grows only by however much longer this job takes than the slowest other
  job.** The jobs run in parallel. Its durations are recorded on #201's PR.
- **Locally, `npm test` now needs Docker for the whole run, and takes minutes longer.** The developer's
  own compose stack has to be down, though their data volume is kept.
- **An interrupted check leaves its containers behind.** Ctrl+C ends the script before it can take
  the stack down, and those containers hold the stack's fixed names. So the developer's own
  `docker compose up` conflicts with them, and a plain `docker compose down` does not remove them,
  because they belong to the check's project. The next run clears them first (step 1), and the
  README gives the one-line `down` too. Catching the signal so the script tears down after an
  interrupt was not done: how Windows delivers Ctrl+C to a child started with `spawnSync` was not
  tested here. Revisit if a leftover stack is reported in practice.
- **A developer's environment applies to the local check.** Compose fills `docker-compose.yml`'s
  variables from a root `.env` and from the shell, so an exported `JWT_SECRET` or
  `SPRING_PROFILES_ACTIVE` changes the stack the check starts. CI sets none of them.
  - Stripping them in the script would mean a hand-kept copy of the variable list, the drift this
    record removes elsewhere.
  - A developer who exports one does so on purpose.
  - So it is stated rather than prevented.
- **The check needs a Compose that understands `!reset`.** What has been shown:
  - On Compose 5.6.0 locally, `config` with the override renders no `ports` on any service.
  - The runner's Compose 2.38.2 (image `ubuntu-24.04` 20261012.334) accepts the file, in run
    39150920073. The runner has nothing on 3000, 5432 or 8080, so a green run there cannot show that
    the ports were reset, only that the tag parsed.
  - The script prints `docker compose version` first, because the runner image changes under the
    check.
- **`ContainerImagesArePinnedTest` reads `scripts/compose.images-check.yml`**, because its name matches
  the test's pattern. The file names no image, so it adds nothing to check, and any image added to it
  later is held to an exact tag like the rest.

## Alternatives considered

- **Two bare `docker build`s.** Rejected. They show that each Dockerfile runs, and nothing more: not
  that Flyway migrates an empty database in the image, and not that nginx accepts its configuration.
- **Build the backend image from the backend job's jar.** Rejected. It needs a second, runtime-only
  Dockerfile, which would test an image nobody publishes, and skip the in-image Maven build this job
  exists to exercise.
- **Scan the images with Trivy here.** Rejected for now. Trivy is Apache-2.0 and actively released,
  but it is a new third-party tool that runs with the job's token. Its distribution was compromised on
  2026-03-19 (a malicious v0.69.4, and `trivy-action` tags force-pushed to credential-stealing code,
  GHSA-69fq-xp46-6x23).
  - **Revisit when** an image is published somewhere that does not scan it, or a vulnerability reaches
    a running image that a PR scan would have caught. Decide it together with the backend audit (#59).
- **A buildx layer cache through the GitHub Actions cache backend.** Rejected for now. It pays only
  with the `docker/*` actions, which #154 turned down.
  - **Revisit when** the job takes longer than about five minutes. A dependency layer in
    `backend/Dockerfile` (`COPY pom.xml`, resolve, then `COPY src`) would be the first step, and it also
    speeds up every local rebuild.
- **Reset the container names and move the subnet in the override, so the check can run beside the
  developer's stack.** Rejected. The frontend's fixed address is what `TRUSTED_PROXIES` trusts, so moving
  the subnet means overriding that too. The check would then run a configuration that ships nowhere.
  Refusing with a clear message costs one `docker compose down`.

## When to revisit

- **The job passes about five minutes**, or starts deciding how long a PR waits: add the dependency
  layer, then the cache above.
- **An image is published** (#154): scanning (above), and whether the publish job should reuse this
  one's build.
- **The fixed `container_name`s or subnet go away** (#213 or a deployment change): the refusal in
  step 2 can go with them.
