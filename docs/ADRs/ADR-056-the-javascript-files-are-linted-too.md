# ADR-056: The JavaScript files are linted too, the gate's own scripts included

- **Status:** Accepted
- **Date:** 2026-10-29
- **Extends:** [ADR-035](ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md), taking up the
  alternative it deferred: "Lint the `.js` and `.mjs` files too"
- **Scope:** `frontend/eslint.config.js`; the `lint` and `lint:scripts` scripts in
  `frontend/package.json`. CI runs `npm run lint` inside `npm run test:frontend` as before, so
  `.github/workflows/ci.yml` does not change. NFR-12 in `docs/requirements/requirements.md`.
- **Issue:** [#336](https://github.com/NaimElijah/UpHealther/issues/336), scan 02 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding I2.15.

## Context

ADR-035 kept ESLint's scope where it was: `.ts` and `.tsx` files get rules, and the `.js` and `.mjs`
files are visited but matched by nothing. It deferred widening that scope, with a trigger:
"**Revisit:** with the formatter (#161), which touches every file anyway". #161 closed on 2026-10-15.
Nobody revisited, and scan 02 found the gap.

The files left out are not incidental. They are Node's, and some of them run the gate:

- `scripts/check-images.mjs`, `scripts/mvnw.mjs` and `scripts/python.mjs`, at the repository root:
  the image check, and the launchers `npm test` runs through (ADR-044, ADR-045);
- `frontend/scripts/check-colours.mjs`, which CI runs as `npm run check:colours`;
- the frontend's tool configurations: `eslint.config.js`, `postcss.config.js`, `prettier.config.js`
  and `tailwind.config.js`.

Prettier already formats the root `scripts/` from `frontend/` (ADR-038). Nothing lints them.

A slip that only lint would catch can turn the gate green on a broken stack. `if (status = 0)` written
in `check-images.mjs`'s `finally` block assigns, reads as false, and the script reaches
`process.exit(0)` after a failed suite. Today's lint passes that line. ESLint's `recommended` set
reports it three ways: `no-cond-assign`, `no-constant-condition` and `no-useless-assignment`.

**ESLint 10 lints nothing above its configuration's directory.** With the configuration found by
lookup, a file in `../scripts` finds no `eslint.config.*` beside it. Named with `--config` from
`frontend/`, it is "ignored because outside of base path". With `basePath: '..'` on a configuration
object, ESLint still ignored `../scripts`. What works is running from the repository root with
`--config frontend/eslint.config.js`: the base path is then the root, and the configuration's imports
still resolve from `frontend/node_modules`, beside the file.

## Decision

**Every `.js` and `.mjs` file the frontend's ESLint can reach gets `js.configs.recommended`, with
Node's globals, and the root's `scripts/*.mjs` are linted by the same configuration.**

- `eslint.config.js` gains one block: `files: ['**/*.{js,mjs}']`, `js.configs.recommended`, and
  `globals.node`. The `.ts` and `.tsx` block is unchanged.
- `npm run lint:scripts` runs `cd .. && eslint --config frontend/eslint.config.js
  --report-unused-disable-directives --max-warnings 0 scripts`. It keeps the `cd dir && tool args`
  form that runs under `cmd.exe` and `sh` alike (ADR-044).
- `npm run lint` runs the frontend's lint and then `lint:scripts`. So CI, the root `npm test` and a
  developer's `npm run lint` all cover both.

Every file in scope passed the widened lint the day it was turned on, so it needed no fixes.

## Consequences

- **The gate's own logic is linted.** An assignment in a condition, a constant condition, an unused
  variable or an unreachable branch in a script `npm test` runs now fails `npm run lint`. That mutation
  of `check-images.mjs` exits 1 under the new lint and 0 under the old.
- **One configuration covers two roots.** A rule added for the frontend's `.js` files applies to the
  root scripts too. Both are Node ES modules, so the same rules fit.
- **The root still installs nothing.** The lint packages stay `frontend/`'s, and `lint:scripts` needs
  `npm run setup:frontend` first, as `format:check` already does for the same files.
- **`e2e/` is still neither linted nor type-checked.** Its `.ts` files are outside `frontend/` too, and
  linting them is a separate decision, with its own `tsconfig` question.

## Alternatives considered

- **A root `eslint.config.js`.** Rejected. Its imports would resolve from the root, which installs
  nothing (ADR-044). Making them resolve would mean a root install, or imports that reach into
  `frontend/node_modules` by path.
- **`node --check` on the root scripts.** Rejected. It parses, and nothing more: `if (status = 0)` is
  valid syntax.
- **Leave them unlinted, and record why.** Rejected. ADR-035's own trigger has fired, and the gate's
  scripts are where a silent pass costs most.

## When to revisit

- **`e2e/` gets a type-check or a lint of its own:** it can join the root-run lint, or carry its own.
- **ESLint lets a configuration reach above its directory:** `lint:scripts` can fold back into `lint`.
- **The root starts installing packages:** a root configuration becomes the simpler home.
