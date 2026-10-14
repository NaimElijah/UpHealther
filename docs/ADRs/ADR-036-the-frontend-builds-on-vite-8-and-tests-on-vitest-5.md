# ADR-036: The frontend builds on Vite 8 and tests on Vitest 5

- **Status:** Accepted
- **Date:** 2026-10-14
- **Scope:** `frontend/`. The `vite`, `@vitejs/plugin-react`, `vitest`, `@vitest/coverage-v8` and
  `jsdom` development dependencies, plus `vite.config.ts`, `vitest.config.ts` and
  `tsconfig.node.json`. It supersedes [ADR-004](ADR-004-frontend-test-harness.md)'s Vitest `^3` and
  jsdom `^29` pins. ADR-004's choice of harness stands.
- **Issue:** [#166](https://github.com/NaimElijah/UpHealther/issues/166), step 4, the last. Step 1 was
  [ADR-031](ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md), and step 3 was
  [ADR-035](ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md).

## Context

Three pins held each other on Vite 5:

- Vitest stayed at `^3.2.7`, because Vitest 4 needs Vite 6 or later (ADR-004).
- jsdom stayed at `^29`, because jsdom 30 needs Node 22.22 or 24.15 and CI ran Node 20 (ADR-004).
  [ADR-031](ADR-031-the-frontend-toolchain-runs-on-node-24-lts.md) moved Node to 24, which removed
  that reason, but left the pin to this step.
- `@vitejs/plugin-react` 4.7 accepts Vite up to 7.

The installed versions were Vite 5.4.21, Vitest 3.2.7, `@vitejs/plugin-react` 4.7.0 and jsdom 29.1.1.

The current releases, checked on 2026-10-14:

| Package | Version | Released | Accepts |
|---|---|---|---|
| `vite` | 8.3.4 | 8.0 on 2026-03-12 | Node 20.19, or 22.12 and later |
| `@vitejs/plugin-react` | 6.1.2 | 6.0 on 2026-03-12 | Vite 8 only |
| `vitest` | 5.0.4 | 5.0 on 2026-09-03 | Vite 6.4, 7 or 8; Node 22.12, 24, or 26 and later |
| `@vitest/coverage-v8` | 5.0.4 | | The same Vitest version exactly |
| `jsdom` | 30.1.2 | 30.0 on 2026-07-27 | Node 22.22, 24.15, or 26 and later |

The issue asks for the four to move together, with jsdom 30 if Node 24 admits it. It asks that the
production build and the dev proxy behave exactly as before.

## Decision

**Move to Vite 8, `@vitejs/plugin-react` 6, Vitest 5 and jsdom 30, in one change, and keep the build's
browser target where Vite 5 had it.**

- **Vite 8 and plugin-react 6.** Vite 8 bundles with Rolldown, and minifies CSS with Lightning CSS by
  default. plugin-react 6 no longer depends on Babel. Neither needs a new option for this project,
  beyond the target below.
- **Vitest 5 and `@vitest/coverage-v8` 5.** Vitest 4 has had no release since 5.0 shipped: its last
  is 4.1.11, from 2026-08-18. All 39 test files and 413 tests pass unchanged on Vitest 5 with
  jsdom 30.
- **The build target is pinned to Vite 5's old default.** `vite.config.ts` sets `build.target` to
  `['es2020', 'edge88', 'firefox78', 'chrome87', 'safari14']`.
  - Vite 7 changed the default to `baseline-widely-available`, which in Vite 8.3 is Chrome 111,
    Edge 111, Firefox 114 and Safari 16.4.
  - With that default, the build changes beyond syntax. Lightning CSS merges Tailwind's separate
    `::backdrop` rule into the `*` rule, so a browser without `::backdrop` drops the whole rule.
  - With the old target, the CSS keeps the separate rule, and both bundles are within 0.3% of Vite
    5's size.
  - Nothing in the repository states which browsers are supported, so the pin keeps what was there
    rather than letting a tool upgrade decide it. That decision is
    [#250](https://github.com/NaimElijah/UpHealther/issues/250).
- **`vitest.config.ts` imports `./vite.config.ts` with its extension.** Vite 8 warns about an
  extensionless import on every run, because its native config loader, planned to become the default,
  cannot resolve one. `tsconfig.node.json` gains `allowImportingTsExtensions` with
  `emitDeclarationOnly`. `noEmit` would make the root project's reference to it fail (TS6310).

## Consequences

- **#166 is finished.** The whole frontend toolchain is on supported majors: Node 24, ESLint 10,
  Vite 8 and Vitest 5. ADR-004's two pins are gone.
- **Coverage numbers moved.** `@vitest/coverage-v8` 5 converts coverage only through
  `ast-v8-to-istanbul`. Version 3 used `v8-to-istanbul` by default. The same tests now count 1,379
  statements rather than 3,354, across the same 73 files. Coverage gates nothing
  ([ADR-009](ADR-009-test-levels-boundaries-and-naming.md)), so only the printed summary changes.
- **The browser floor is unchanged, and still unstated.** The pin is a status quo, not a decision. It
  goes away with #250.
- **Vitest 5 is six weeks old.** It is the youngest major in the toolchain. A defect in it would show
  as a test failure that names nothing in this project, which is how ADR-004's jsdom failure looked.

## Alternatives considered

- **Vite 7 and Vitest 4.1.** Rejected. Vite 7 still gets patches (7.3.7 on 2026-10-06), but Vitest 4
  has had none since 5.0 shipped. Either way it is the previous major, and the move would be needed
  again soon. Nothing in this project needed 7 over 8.
  - **Revisit:** if a Vite 8 or Vitest 5 defect blocks the build or the suite, and no patch follows.
- **Take Vite 8's browser default.** Rejected for now. It changes which browsers the bundle is lowered
  for, without anyone choosing to. #166 asks for the build to behave exactly as before.
  - **Revisit:** when #250 decides the supported browsers.
- **Silence the config-loader warning** with `VITE_CONFIG_NATIVE_IGNORE_WARNING`. Rejected. The
  extension costs one character and two compiler options, and it keeps the config loadable once the
  native loader becomes the default.

## When to revisit

- **Vite 9 or Vitest 6 is released.**
- **#250 states the supported browsers.** Replace or delete the pinned target then.
