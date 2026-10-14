# ADR-035: The frontend lints with ESLint 10 and a flat config

- **Status:** Accepted
- **Date:** 2026-10-14
- **Scope:** `frontend/eslint.config.js`, which replaces `frontend/.eslintrc.cjs`; the lint packages in
  `frontend/package.json`; and the `lint` script. CI runs `npm run lint` as before, so
  `.github/workflows/ci.yml` does not change.
- **Issue:** [#166](https://github.com/NaimElijah/UpHealther/issues/166), step 3. Step 1 was
  [ADR-031](ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md).

## Context

The frontend linted with ESLint 8.57.1, which reached end of life on 2024-10-05. It was configured
through `.eslintrc.cjs`, a format ESLint 9 reads only behind a flag and ESLint 10 not at all. Two
plugins held it at 8:

- `@typescript-eslint/eslint-plugin` and `parser` 6.21 accept only ESLint 7 or 8;
- `eslint-plugin-react-hooks` 4.6 stops at 8.

The current releases, checked on 2026-10-14:

| Package | Version | Note |
|---|---|---|
| `eslint` | 10.12.0 | `latest`. 10.0 shipped 2026-02-06. 9.39.5 is on the `maintenance` tag |
| `@eslint/js` | 10.0.1 | Holds `eslint:recommended` since ESLint 9 |
| `typescript-eslint` | 8.71.2 | Accepts ESLint 8.57, 9 or 10, and TypeScript up to 6.0. The frontend runs 5.9.3 |
| `eslint-plugin-react-hooks` | 7.1.1 | Accepts ESLint up to 10. Its `recommended` set now includes the React Compiler's rules |
| `eslint-plugin-react-refresh` | 0.5.7 | Accepts ESLint 9 or 10 |
| `globals` | 17.13.0 | Global-variable lists, which flat config takes in place of `env` |

#166 asks for the current supported major, "9 at minimum", with `--max-warnings 0` kept, and new
findings "fixed rather than switched off".

## Decision

**Lint with ESLint 10 through a flat config, `eslint.config.js`, taking each preset whole.**

- **One block** applies to `**/*.{ts,tsx}`. It extends:
  - `js.configs.recommended`;
  - `tseslint.configs.recommended`;
  - `reactHooks.configs.flat.recommended`;
  - `reactRefresh.configs.vite()`.

  Its globals are `globals.browser`, and `dist` and `coverage` are ignored.
- **The scope is unchanged.** Only `.ts` and `.tsx` files get rules, as under the old `--ext ts,tsx`.
  The four `.js` and `.mjs` files are visited, but no rule applies to them.
- **`npm run lint`** keeps `--report-unused-disable-directives --max-warnings 0` and drops `--ext`.
- **react-hooks' `recommended` is taken with the React Compiler's rules,** although the compiler is not
  used. Each rule names something worth avoiding with or without the compiler: a `setState` inside an
  effect costs a second render, and a write to an outer variable during render is a side effect that
  StrictMode runs twice.
- **react-refresh's Vite preset** keeps `allowConstantExport: true`. It adds `allowCompoundComponents`,
  which `@vitejs/plugin-react` has supported since 4.7, and it raises the rule from `warn` to
  `error`. Under `--max-warnings 0` that changes nothing.

**What the new presets found** was five findings, all fixed before the switch, each change in its own
commit. They passed the old lint and the full test suite:

| Rule | Where | Fix |
|---|---|---|
| `react-hooks/set-state-in-effect` | `ThemeProvider`, which mirrored `matchMedia` into state from an effect | `useSyncExternalStore` |
| `react-hooks/set-state-in-effect` | `UpgradeActions`, which cleared the "focus Reschedule next" flag from the effect that acts on it | The flag is cleared when the dialog next opens |
| `react-hooks/globals` | Three tests, which assigned the router's `navigate` to a module variable during render | They assign it in an effect |

None changed behaviour. The tests covering the two components were checked by breaking each changed
path, and they fail: removing the unsubscribe or the subscription, or the focus call.

A first attempt made the `UpgradeActions` flag a ref instead. Review found it changed behaviour. An
effect that depends only on the dialog does not run when the flag is set after the dialog has
already closed, and the ref then stayed set and moved focus on a later Cancel. Two tests now pin both
paths. A ref is not a drop-in replacement for state an effect depends on, because changing a ref runs
nothing.

**New dependencies** (all MIT, all development-only, so `npm audit --omit=dev` is unaffected):

- `@eslint/js` (the ESLint project). Without it there is no `eslint:recommended` since ESLint 9.
- `globals` (sindresorhus). It is the replacement flat config documents for `env: { browser: true }`.
- `typescript-eslint` (the typescript-eslint project). It replaces the two packages
  `@typescript-eslint/eslint-plugin` and `parser`, as one.

## Consequences

- **The linter is supported again,** and nothing left in the lint toolchain blocks the next major.
- **New code meets the React Compiler's rules,** which are stricter than React 18 requires.
  `frontend/CLAUDE.md` names the two most likely findings and their usual fix. If the compiler is ever
  adopted, the code is already written to its rules.
- **`.js` and `.mjs` files are still not linted.** That includes `scripts/check-colours.mjs`, which CI
  runs. This decision leaves the old scope as it was.
- **The configuration is an ES module.** That works because `package.json` sets `"type": "module"`.

## Alternatives considered

- **ESLint 9.** Rejected. It is already on the `maintenance` tag, so it would buy a second migration
  soon. Nothing in this toolchain needs 9 rather than 10.
  - **Revisit:** if a plugin the frontend needs supports 9 but not 10.
- **Only react-hooks' two classic rules** (`rules-of-hooks` and `exhaustive-deps`), without the
  compiler's. Rejected. #166 asks for the presets taken whole, and the compiler's rules found two real
  extra renders. Fixing them took three small commits.
  - **Revisit:** if a compiler rule's only fix is a behaviour change with no correctness gain.
- **`recommended-latest`**, react-hooks' experimental rule set. Rejected. The plugin describes it as
  bleeding edge, and a lint gate that fails CI should not move with experiments.
  - **Revisit:** when the React Compiler is adopted.
- **Lint the `.js` and `.mjs` files too.** Not done here. It widens what lints rather than upgrading
  the tool, and #166 asks for the upgrade.
  - **Revisit:** with the formatter (#161), which touches every file anyway.

## When to revisit

- **ESLint 11 is released,** or 10 moves to `maintenance`.
- **The React Compiler is adopted.** See `recommended-latest` above.
- **A compiler rule demands a behaviour change** that buys no correctness.
