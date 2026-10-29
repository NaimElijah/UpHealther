# ADR-052: A Playwright smoke suite drives the stack the image check starts

- **Status:** Accepted — extended by
  [ADR-059](ADR-059-the-bundle-ships-its-third-party-notices.md), which adds a second test that reads
  the third-party notices nginx serves
- **Date:** 2026-10-22
- **Scope:**
  - `e2e/`: its `package.json`, lockfile, `.npmrc`, `playwright.config.ts` and `tests/smoke.test.ts`;
  - `scripts/check-images.mjs` and `scripts/compose.images-check.yml`;
  - the root `package.json`'s `setup`, `setup:frontend` and `setup:e2e`;
  - the `frontend` and `images` jobs in `.github/workflows/ci.yml`, and `.github/dependabot.yml`'s
    `/e2e` entry;
  - the `format` and `format:check` scripts in `frontend/package.json`;
  - NFR-56, and NFR-12, NFR-51, NFR-52 and NFR-53, in `docs/requirements/requirements.md`.
- **Issue:** [#167](https://github.com/NaimElijah/UpHealther/issues/167), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It is the addition
  [ADR-004](ADR-004-frontend-test-harness.md) said a real-browser runner would be, once its revisit
  trigger fired. It extends [ADR-009](ADR-009-test-levels-boundaries-and-naming.md),
  [ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md),
  [ADR-038](ADR-038-the-frontend-is-formatted-by-prettier.md),
  [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md),
  [ADR-045](ADR-045-ci-builds-and-starts-both-images.md) and
  [ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md).

## Context

- **No test exercised the frontend and the API together.** The backend's `*IT`s run the API against a
  real PostgreSQL and never load the bundle. The frontend's tests mock the API, and a mock encodes
  what its author believes the API does, so it agrees with the component it was written for.
- **ADR-004's trigger fired.** It deferred a real-browser runner until "there is a user-visible
  regression that a unit test could not have caught". Three closed bugs are that:
  - #88: Plan posted no start date, so every plan was refused and nothing created in the UI could
    leave `IDEA`. The controller test pinned the refusal; nothing covered the call.
  - #20: the API sent the weekly rate as a percentage and the dashboard multiplied it by a hundred
    again. A mocked response written as a fraction agrees with the bug.
  - #75: the frontend's healthcheck could never pass. ADR-045's image check now catches that class.
- **The stack is already built and started on every PR.** ADR-045's image check does it in about two
  minutes, in parallel with the backend job, which sets CI's wall clock at about three. Its override
  file kept one published port for this suite.
- **The stack accepts one origin.** CORS and the `/ws` origin check allow `http://localhost:3000`, and
  compose does not pass `CORS_ALLOWED_ORIGINS`. nginx forwards `Host` without the port, so every POST
  from the browser reaches Spring as cross-origin, and the allow-list decides it. The refresh cookie is
  always `Secure` ([ADR-041](ADR-041-the-refresh-cookie-is-always-secure.md)), which a browser keeps
  over plain HTTP only on `localhost`.
- **Sign-in and registration share ten attempts a minute per address** (NFR-42). Compose does not pass
  `AUTH_RATE_LIMIT` through, so a run has to fit inside the default.
- **#61's layout half needs the same tool:** a real browser, at several widths.

## Decision

### One smoke test, against the stack the image check starts

`e2e/tests/smoke.test.ts` holds one test,
`GivenANewVisitor_WhenTheyActivateAnUpgradeAndLogToday_ThenTheDashboardCountsIt`. Each step shows
something only the running stack can:

| Step | What only the running stack shows |
|---|---|
| Register, sign out, sign in again | The CORS join through nginx; the `Secure` refresh cookie set and cleared on `localhost`; the notification socket subscribing through nginx's `/ws`, past the origin check and STOMP authentication |
| Create an idea, plan it, activate it | #88's join: Plan's request carries the start date the API requires. The activation's notification is pushed over the socket and shown as a toast |
| Log today's progress | The entry, dated by the browser's day, is accepted by the server |
| Read the dashboard after a reload | #20's join: the API's percentage is shown as `100%`, and the active upgrade is counted |

The test also fails on an uncaught page error, and on a Content Security Policy report in the console.
nginx is the only place the policy is served, so no other suite sees a violation.

It is the happy path only. Failure paths stay at the cheapest level that can observe them (ADR-009).
It does not use the demo account, which #84 means to remove; each run registers its own.

### It runs inside the image check

`scripts/check-images.mjs` now:

1. before any Docker work, resolves Playwright's command-line entry point and launches and closes the
   Chromium it drives, and stops pointing at `npm run setup:e2e` when either is missing. The browser
   lives in Playwright's cache, outside `e2e/`, so a bare `npm ci` there after a Playwright bump leaves
   the package new and the browser absent. The suite runs Chromium's headless shell, whose path no
   public API gives, so the check launches it rather than looking for a file;
2. after its existing clean-up and name check, refuses to start while something answers on an address
   the merged configuration publishes, before minutes of building end in Docker's bind error;
3. after `up --wait`, runs the suite with `node <cli> test`, so no shell parses anything, as in
   `mvnw.mjs`;
4. prints the stack's logs when the suite fails, and takes the stack down whatever happened, as before.

**`scripts/compose.images-check.yml` publishes `127.0.0.1:3000` for the frontend.** It has to be 3000,
for the origin. It is bound to the loopback address, so the stack is never reachable from the network.
`!override` replaces the base file's `3000:80` instead of adding a binding beside it, and needs Compose
2.24.4 or later. On Compose 5.6.0, `config` with the override renders that one port and no other.

**CI runs it in the existing `images` job, on every PR.** The job gains a `npm run setup:e2e` step of
its own, so its time shows in the run, and uploads the suite's trace and screenshots when it fails. Its
`name:`, a required check of ruleset 25310599, is unchanged. #167 proposed running the suite only on PRs
into `main`, because it assumed a job that built its own images. That build is already paid for.

### The harness

**New development dependency.** It never ships.

| Dependency | Replaces | Licence | Maintenance |
|---|---|---|---|
| `@playwright/test` 1.64.1, with the Chromium it pins | The manual pass before a release, and, once #61 lands, the by-hand width checks | Apache-2.0 | Maintained by Microsoft; 1.64.1 is the latest stable release |

- **`e2e/` is a package of its own**, with its own lockfile, outside the frontend's. It is not in the
  frontend image's `npm ci`, not in the frontend's audit, and not under its lint rules, where
  react-hooks would read Playwright's fixture callback `use` as a hook. Its `engines` range is the
  frontend's and its `.npmrc` sets `engine-strict` (ADR-030), so CI's Node from `frontend/.nvmrc`
  serves both. It has no `version`, because NFR-54's version names what ships.
- **Chromium only**, the build Playwright pins, installed by `npx playwright install chromium`. Never
  `--with-deps`, which runs `sudo apt-get` on a Linux machine.
- **The configuration:**
  - `baseURL` is `http://localhost:3000`, the one origin.
  - Chromium resolves `localhost` to `127.0.0.1` itself. Docker Desktop accepts a connection on `::1`
    and then stalls it, and this keeps the origin `localhost`.
  - The browser runs in UTC, the server's zone (ADR-020). The SPA dates an entry by the browser's day,
    so east of UTC, late in the day, an entry would be tomorrow's to the server.
  - A 1280×720 viewport, where the sidebar the test navigates by is shown.
  - One worker, no retries, and `.only` refused everywhere, as `vitest.config.ts` refuses it.
  - A trace and a screenshot are kept only when the test fails.
- **How the test finds things.** By role and label, the way the frontend's own tests do. Dialogs are
  scoped explicitly, because a card and its dialog both have a Plan button. It follows the sidebar's
  links rather than loading pages, because each load renews the session from the rotating refresh
  cookie; the one load is the dashboard's, to drop its cached figures. It waits for the socket's
  SUBSCRIBE frame before going on, so the toast it asserts cannot be pushed before anyone listens.
- **No lint or type check over `e2e/`**, as for `scripts/*.mjs` (ADR-044). Prettier formats it
  (ADR-038). The risk is an assertion that is not awaited, which passes whatever happens and which
  `tsc` would not catch either. The guard is that each key assertion was shown failing:
  - with the `Upgrade` and `Connection` headers removed from `nginx.conf`'s `/ws` location, the test
    fails in 13 seconds on the subscription. No other suite reads that block;
  - with #20 reintroduced in `DashboardPage.tsx`, it fails on `Expected: "100%"`,
    `Received: "10000%"`.
- **Dependabot proposes its updates in an entry of its own.** A Playwright release moves the Chromium
  it drives, so it arrives in a PR of its own, whose CI run shows whether the new browser still passes.
- **The root `setup` now runs `setup:frontend` and `setup:e2e`.** CI's frontend job runs only the first
  and the images job only the second, as each runs one `test:*` script.

## Consequences

- **A disagreement between the frontend and the API on the main path fails the PR**, before it reaches
  `dev`: a request the API refuses, a figure the page misreads, a proxy that breaks the socket.
- **A first run took 155 seconds locally**, building with an empty cache; the suite took 9.5 of them.
  CI's times are recorded on #167's pull request.
- **`npm run setup` downloads about 315 MiB**: Chrome for Testing and its headless shell, once per
  Playwright version, into Playwright's cache outside the repository.
- **`npm test` needs `127.0.0.1:3000` free.** The check refuses up front when it is not. Vite's dev
  server on a machine that resolves `localhost` to `::1` listens there only, and does not collide.
- **Each run spends two of the ten sign-in attempts a minute.** `cd e2e && npm test` against a
  developer's own `npm start` stack works too, and leaves an account in it each time.
- **The suite says nothing about a user in another time zone**, because it pins the browser to UTC.
  The browser and the server must also share a clock: in CI they read one kernel's, but Docker
  Desktop's virtual machine can drift after the host sleeps.
- **The required check's name, `Images - Build & Start`, no longer lists everything the job does.** The
  step and the documents say it drives a browser.
- **Nothing here reaches an image.** `e2e/` is outside both build contexts.

## Alternatives considered

- **Cypress** (MIT). Rejected. #167 asked for the two to be compared on #61's need and on CI install
  cost:
  - **Several widths in one run.** Playwright runs the same spec once per project, and a project can be
    a viewport, so #61's six widths are configuration. In Cypress a test sets its own viewport.
  - **Install cost.** Playwright's browser download is a separate, explicit step, timed on its own in
    CI. Cypress downloads its own binary when the package installs. Its size was not measured; Cypress
    was not installed or run for this decision.
- **Vitest browser mode.** Rejected for this, as ADR-004 rejected it for unit tests. It renders
  components in a real browser, but nginx, the API and the socket stay mocked, and they are what this
  suite is for.
- **A CI job of its own.** Rejected. It would build both images a second time, about two minutes, and
  add a fifth required check.
- **Only on PRs into `main`**, as #167 proposed. Rejected while the build is shared; see the triggers.
- **The machine's own Chrome, through Playwright's `channel`.** Rejected. Its version would float with
  the runner image and each developer's updates, where the bundled build is pinned (ADR-030).
- **Playwright's container image on the compose network.** Rejected. The page would be served from the
  service's name, an origin CORS refuses, over plain HTTP on a host where the `Secure` cookie is
  dropped.
- **Specs inside `frontend/`.** Rejected. Playwright would join the frontend's lockfile and its image's
  install, and its fixture callback would trip the react-hooks rules.
- **Retries.** Rejected. A step that fails without a code cause is fixed, not retried.
- **Renaming the `images` job.** Deferred. It means editing ruleset 25310599 just before the merge and
  re-triggering every open PR (ADR-043), for a label.

## When to revisit

- **The images job runs a minute longer than the backend job, or passes ADR-045's five minutes:** give
  the backend's Maven download a layer of its own in its Dockerfile first. Run the suite only on PRs
  into `main` after that, if it is still the slowest job.
- **The suite fails twice in 30 days with no code cause:** find and fix the race. Do not add retries.
- **#61's layout half lands:** add its widths as projects, budget the ten sign-ins a minute across them,
  and never share a saved sign-in between tests, because the refresh cookie rotates on use. Rename the
  `images` job then, while the ruleset is being edited for nothing else.
- **A second spec, or a helper shared between specs:** add `typescript` and `tsc --noEmit` to `e2e/`;
  add a lint rule for floating promises after the first assertion found not awaited.
- **One more root script:** that passes ADR-044's "about a dozen", and its trigger to move to `just`.
- **Firefox or WebKit is needed:** WebKit's handling of the `Secure` cookie on `localhost` was never
  checked (ADR-041).
