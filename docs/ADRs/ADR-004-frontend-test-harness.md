# ADR-004: Test the frontend with Vitest, jsdom and Testing Library

- **Status:** Accepted
- **Date:** 2026-08-19
- **Scope:** `frontend/` only. No effect on the backend, the runtime, or anything that ships to a
  browser — every package added here is a development dependency.

## Context

[ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md) built a floor under
the backend: an integration test that boots the application against a real PostgreSQL, ArchUnit rules
that fail on a layering violation, and tests for the two services that had none. It left the frontend
without an equivalent, and `docs/requirements/requirements.md` §6 has recorded that gap as an open
question ever since:

> **The frontend has no test runner.** No vitest, no jest. Frontend logic is currently verified only by
> the type checker, the linter and inspection. Introducing one is a dependency decision that deserves
> an ADR.

This is that record.

Until now the gap was tolerable because the frontend held almost no logic worth testing. Its
components render server state that TanStack Query fetches, and the one piece of real behaviour —
that the mirrored enums cannot drift from the backend's — is already enforced from the backend by
`FrontendEnumContractTest`. Type checking and a zero-warning lint genuinely covered the rest.

Dark mode changes that. `ThemeProvider` holds branching logic that no type can express and no reviewer
can reliably eyeball:

- an explicit choice must beat the operating system's preference, but only when it is explicit;
- `system` must follow the OS **while the tab is open**, which means a `matchMedia` subscription with a
  cleanup that `React.StrictMode` will exercise twice on every mount;
- reading `localStorage` throws outright where site data is blocked, and there is no error boundary
  above this provider, so an unguarded read blanks the whole application.

Each of those is a small, pure, deterministic assertion. Each is also the kind of defect that ships
green and surfaces months later on somebody else's machine.

## Decision

Add **Vitest** with **jsdom** and **Testing Library** as development dependencies, and run them in CI
between the lint and the audit.

Four packages, all `devDependencies`: `vitest`, `jsdom`, `@testing-library/react`,
`@testing-library/dom`. Nothing reaches a user's browser, so `npm audit --omit=dev` — the audit scope
ADR-002 chose deliberately — is unaffected.

Four choices inside that decision are worth stating, because each has a failure mode:

- **Vitest is pinned to `^3.2.7`, not `^4`.** Vitest 4 declares a peer dependency on Vite `^6 || ^7 ||
  ^8`; this project is on Vite 5. Taking Vitest 4 would mean a Vite major upgrade riding along inside a
  styling change. Vitest 3 depends on Vite `^5.0.0 || ^6.0.0 || ^7.0.0-0` and needs nothing moved.
- **jsdom is pinned to `^29`, not `^30`.** jsdom 30 declares `engines: ^22.22.2 || ^24.15.0 || >=26.0.0`
  and pulls `undici@8`, which needs Node `>=22.19.0`. CI and the frontend Dockerfile both run Node 20,
  so jsdom 30 installs with an `EBADENGINE` warning and then dies at import with
  `webidl.util.markAsUncloneable is not a function` — an error that names nothing in this project and
  appears only on the CI runner, never on a developer machine running a newer Node. jsdom 29 accepts
  `^20.19.0 || ^22.13.0 || >=24.0.0`, which covers both. Same principle as the Vitest pin: the harness
  bends to the project's runtime, not the other way round.

  This one bit before it was understood — the CI test step was added and merged without ever running,
  so the mismatch stayed invisible until the first pull request. **Node 20 reached end of life in April
  2026**, so the real resolution is to move the whole toolchain, including `frontend/Dockerfile`, off
  it; that is a deployment change and belongs in its own ADR rather than riding along here.
- **Configuration lives in `vitest.config.ts`, merged over `vite.config.ts` with `mergeConfig`.** A
  `test` key inside `vite.config.ts` would make the production build configuration type-depend on a
  development-only package. Keeping them separate without the merge is worse: Vitest reads
  `vitest.config.ts` *instead of* `vite.config.ts` when both exist, so the React plugin and the dev
  proxy would silently stop applying to tests. The merge is what keeps one source of truth.
- **No globals.** `describe`, `it` and `expect` are imported in each test file rather than injected
  ambiently. This leaves `tsconfig.json`'s `types` array — and therefore the production type check —
  untouched, at the cost of one import line per file.
- **Tests are colocated under `src/`, not in a separate tree.** `tsconfig.json` includes `src`, so
  `tsc` type-checks the tests as part of `npm run build`. A test that no longer compiles is a broken
  test and should redden the build.

## Consequences

**What this makes easy**

- Frontend behaviour can be asserted rather than inspected. `requirements.md` entries covering the SPA
  can now name a real test in their "Enforced by" column instead of naming a component and asking the
  reader to trust it.
- The provider's failure paths — blocked storage, absent `matchMedia`, an unrecognised stored value —
  are exercised. None of them are reachable by clicking through the application.
- StrictMode double-invocation is caught by a test that counts live media-query listeners, which is the
  only practical way to notice a dropped effect cleanup.
- The open question in `requirements.md` §6 closes.

**What this makes hard**

- **CI gains a step that can fail, and a slow one relative to the others.** That is the point, but the
  frontend job is no longer "lint and build" and a flaky test would now block merges. There are none
  today; keeping it that way is a standing obligation.
- **Four more development dependencies to keep current**, and Vitest moves quickly. The pin to `^3`
  will eventually need revisiting — see below.
- **Type errors in test files redden the production build**, because `tsc` sees `src/**`. This is
  deliberate, but it means a broken test cannot be left in the tree over a weekend.
- **jsdom is not a browser.** It has no layout engine and no real rendering, so it cannot verify that a
  colour meets a contrast ratio, that a focus ring is visible, or that anything is positioned where it
  should be. Everything visual in this project still rests on inspection.

**Neutral**

- Nothing ships to the browser and no runtime behaviour changes.
- `npm run build` is unchanged; the test run is a separate script and a separate CI step.

## Alternatives considered

- **No test runner — keep relying on `tsc`, ESLint and inspection.** The status quo, and defensible
  right up until the frontend held logic. It does not survive `ThemeProvider`: no type expresses "an
  explicit choice beats the OS preference", and no linter notices a missing `removeEventListener`.
  **Revisit if** the frontend is ever reduced back to pure presentation, which is not a plausible
  direction.

- **Jest.** The most widely used option, and the one most contributors would recognise. Rejected
  because it needs its own transform pipeline — `babel-jest` or `ts-jest` plus a module-resolution
  configuration — duplicating what Vite already does, and that duplicate can drift from the real build
  until a test passes against code that would not ship. Vitest reuses the project's actual Vite
  configuration, so there is exactly one transform. **Revisit if** Vitest is abandoned upstream or the
  project leaves Vite.

- **Playwright, or any real-browser end-to-end runner.** Would catch what jsdom cannot: real rendering,
  real `prefers-color-scheme` emulation, and therefore real contrast and focus-ring verification. Not
  a substitute — it tests a different level, needs the backend and a database running, and is far too
  slow to gate every push. **Revisit when** there is a user-visible regression that a unit test could
  not have caught, or when the manual pass before each release becomes the bottleneck. At that point it
  is an addition to this decision, not a replacement for it.

- **Vitest browser mode** (`@vitest/browser` driving a real Chromium). Removes the jsdom caveat above
  while keeping one runner. Rejected as premature: it needs a browser binary in CI, is materially
  slower, and buys nothing for the pure-logic assertions this harness exists to make. **Revisit when**
  a test genuinely needs layout or computed styles — the contrast checks are the obvious candidate.

- **`@testing-library/jest-dom` for its matchers** (`toHaveClass`, `toBeInTheDocument`). Convenient and
  near-universal. Rejected to keep the dependency count honest: the assertions this harness makes are
  about `classList` and `localStorage`, which plain `expect` states just as clearly. **Revisit if**
  component tests grow to the point where the matchers measurably improve their readability.

## Note

There is no accessibility or visual-regression gate in CI, and this ADR does not add one. Contrast
ratios in the theme were computed by hand and are recorded in the pull request that introduced them;
nothing re-checks them when a colour changes. That is a known gap, and the honest reason it stays open
is that the tooling to close it (a real browser in CI) is the same tooling the browser-mode alternative
above defers.
