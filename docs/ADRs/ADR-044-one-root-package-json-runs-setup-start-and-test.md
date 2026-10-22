# ADR-044: One root `package.json` sets up, starts and tests the repository, and CI runs its scripts

- **Status:** Accepted — extended by [ADR-045](ADR-045-ci-builds-and-starts-both-images.md), which adds
  `test:images` to the end of `npm test` and to CI as a fourth job; and by
  [ADR-047](ADR-047-the-backend-audits-its-dependencies-with-osv-scanner.md), which adds
  `audit:backend` to the end of `test:backend`; and by
  [ADR-051](ADR-051-a-release-is-a-tag-on-its-promotion-and-git-cliff-writes-its-changelog.md), which adds
  `changelog`, a release step that `npm test` does not run; and by
  [ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md), which splits
  `setup` into `setup:frontend` and `setup:e2e`, the second installing the browser suite and its
  Chromium
- **Date:** 2026-10-18
- **Scope:** the root `package.json`, `scripts/mvnw.mjs`, `scripts/python.mjs`, the `format` and
  `format:check` scripts in `frontend/package.json`, and `.github/workflows/ci.yml`. NFR-52 in
  `docs/requirements/requirements.md`.
- **Issue:** [#160](https://github.com/NaimElijah/UpHealther/issues/160), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197).

## Context

The repository baseline asks for one command to set up, one to run and one to test, the same locally
and in CI. Only the run command existed: `docker compose up --build` (NFR-13).

Testing meant several commands across two directories, and the README's list was not CI's:

- **The README** listed `./mvnw test`, `./mvnw verify`, and the frontend's format check, lint, colour
  check and tests.
- **`ci.yml`** spelled out its own list. It also ran the diagram check, the audit and
  `npm run build`, which the README skipped. `build` is the only step that type-checks the test files.

So a branch could pass by the README and fail in CI.

Development happens on Windows, in both PowerShell and Git Bash:

- neither ships `make`;
- Windows PowerShell 5.1 has no `&&`.

Whatever runs the gate has to work from both shells and from `ubuntu-latest`, with one definition.

## Decision

**A root `package.json`, private, with no dependencies and no lockfile, holds three entry points:**

- `npm run setup` installs the frontend's dependencies, with `npm ci` in `frontend/`. Maven resolves
  on the first build, and `generate.py` uses only the standard library, so neither needs a step.
- `npm start` runs `docker compose up --build`.
- `npm test` runs the whole CI gate: `test:diagrams`, `test:frontend`, then `test:backend`.
  - Locally the parts run one after another, cheapest first, so a stale diagram fails in seconds
    rather than after the integration tests.
  - CI runs each part in its own job, in parallel.

**Each CI job runs its part through the same script.** The job `name:`s are unchanged, because they
are ruleset 25310599's required checks (ADR-043). The coverage summary and the artifact uploads stay as
CI-only steps: they report and gate nothing (ADR-009).

**npm runs a script through `cmd.exe` on Windows and through `sh` elsewhere**, whichever shell it was
typed into. So the script bodies are kept to `cd dir && tool args`, which means the same in both. They
contain no quotes, `%`, `^`, `!`, globs or redirects. Two commands cannot be spelled that way, so each
gets a short Node script in `scripts/`, following `frontend/scripts/check-colours.mjs`:

- **`scripts/mvnw.mjs` runs the Maven wrapper.**
  - `cmd.exe` reads `./mvnw` as a command named `.`. `sh` never looks in the current directory for a
    bare `mvnw`. Where `NoDefaultCurrentDirectoryInExePath` is set, as in agent sessions here, `cmd.exe`
    stops looking there too, and only an explicit `.\mvnw.cmd` is found.
  - Node runs a `.cmd` only through a shell (CVE-2024-27980), and an argument array with
    `shell: true` is deprecated (DEP0190). So on Windows the command line is one string, and an
    argument containing whitespace or a character `cmd.exe` would read as syntax is refused. That
    includes parentheses: `mvnw.cmd` expands its arguments inside an `IF … ( … )` block, so a `)`
    ended the block early and the wrapper failed with `… was unexpected at this time`.
- **`scripts/python.mjs` runs the diagram generator.**
  - CI's runner has `python3`. A Windows install has `python` or `py`.
  - A Windows `python3` is often the Microsoft Store's alias, which may be a different Python or may
    only print a prompt.
  - So `python3`, `python` and `py -3` are each asked for their version, and the first Python 3 runs
    the script.

**The root files are formatted too.** `frontend/package.json`'s `format` and `format:check` also cover
`../package.json` and `../scripts`, with `--config prettier.config.js`: Prettier finds no
configuration for a path outside `frontend/` on its own. ESLint lints no `.mjs` file anywhere, so the
root scripts are left to it exactly as `check-colours.mjs` is.

### The frontend gate's order and scope

The CI job used to carry these reasons as comments on six separate steps. They are now one step, and
the reasons live here:

1. **`format:check` comes before `lint`**, so a layout failure is reported as one rather than as a lint
   finding (ADR-038).
2. **`check:colours`** is the only check that catches a mistyped Tailwind colour, because Tailwind emits
   nothing for an unknown class and raises no error (NFR-18).
3. **`test:coverage` runs after the lint**, so a failure there is a behaviour failure, not a style one.
   It is `vitest run --coverage`, not the watcher: a bare `vitest` would hold a CI runner until the job
   timed out. The coverage it prints gates nothing (ADR-004, ADR-009).
4. **The audit is `--omit=dev`.** Auditing the dev tree would redden unrelated PRs whenever an advisory
   lands against Vite or ESLint, and neither reaches a user's browser.
5. **`build` comes last.** It is `tsc && vite build`, the only step that type-checks the test files.

## Consequences

- **`npm test` applies the checks CI applies**, and the README states one command rather than a list
  that drifts. What can still differ is the input:
  - CI installs the frontend's dependencies fresh, while a local run uses whatever the last
    `npm run setup` left, so it is rerun after the lockfile changes.
  - Vitest's `allowOnly` defaults to `!process.env.CI`, which let a committed `.only` pass locally
    and fail in CI. `vitest.config.ts` sets it to `false`, so a focused test fails in both.
- **Node is now the entry point for the backend's gate as well.** `./mvnw` still works directly from
  `backend/`; it is just no longer *the* command.
- **The backend and diagram jobs set up Node**, from `frontend/.nvmrc`. The runner ships Node 22.
- **The frontend's CI step is one step rather than six.** The Actions UI no longer marks which check
  went red, but npm prints a `> <script>` banner before each script it runs, so the last banner above
  the error names it. The audit is the exception: `npm audit` is npm's own command, not a script, so
  it prints `# npm audit report` with no banner, directly under the tests' one.
- **The script bodies are read by two different shells**, and that has to stay safe. Anything beyond
  `cd dir && tool args` goes into a `.mjs` file.
- **`mvnw.cmd` needs `powershell` on `PATH`**, as every stock Windows has. A session without
  `C:\Windows\System32\WindowsPowerShell\v1.0` on `PATH` fails in the wrapper, not here.
- **A stray `npm install` at the root** would write an untracked `package-lock.json` for nothing. It is
  harmless, and visible in `git status`.

## Alternatives considered

- **A Makefile.** Rejected. `make` is preinstalled on the CI runner but not on Windows. There it
  needs a separate install and a POSIX shell for its recipes, so the most common development machine
  here would need the most setup.
- **A `justfile`.** Rejected for now. `just` is one CC0-licensed binary on every platform and actively
  maintained, but:
  - it is a new tool on every machine;
  - each CI job would need an install step;
  - its recipes still run through a shell, so on Windows either Git Bash's `sh` must be on `PATH` or
    PowerShell reads the same recipes the runner's `sh` does.
- **`scripts/*.sh` beside `scripts/*.ps1`.** Rejected: every target written twice, and CI runs only
  one of them. That is drift by construction, the problem this record solves.
- **Keeping the CI steps separate, each calling a root script.** Rejected. Six root scripts for the
  frontend alone, and two lists again: the scripts, and the steps that call them.

## When to revisit

- **Move to `just`** when the recipes need parameters, conditional logic or dependencies between
  targets, or there are more than about a dozen. npm scripts are good at chaining and bad at everything
  else.
- **The root gains a dependency.** It then needs a lockfile and an install step in `setup`.
