# ADR-031: The frontend toolchain runs on Node 24 LTS

- **Status:** Accepted. The rest of #166 has since landed: ESLint 10 in
  [ADR-035](ADR-035-the-frontend-lints-with-eslint-10-and-a-flat-config.md), and Vite 8, Vitest 5 and
  jsdom 30 in [ADR-036](ADR-036-the-frontend-builds-on-vite-8-and-tests-on-vitest-5.md).
- **Date:** 2026-10-13
- **Scope:** `frontend/.nvmrc`, the `engines` field of `frontend/package.json`, `frontend/Dockerfile`'s
  build stage, and the frontend job in `.github/workflows/ci.yml`. It extends
  [ADR-004](ADR-004-frontend-test-harness.md), which asked for this decision. It sits under
  [ADR-030](ADR-030-the-toolchain-is-pinned-in-the-repository.md), which says how the version is
  pinned.
- **Issue:** [#166](https://github.com/NaimElijah/UpHealther/issues/166) (this decision and the move),
  carried out in [#165](https://github.com/NaimElijah/UpHealther/issues/165)

## Context

The frontend builds and tests on Node 20, which reached end of life on 2026-04-30. ADR-004 already said
the toolchain had to move off it, and that the move "belongs in its own ADR rather than riding along
here".

Node is build-only in this project. `frontend/Dockerfile` compiles a static bundle and nginx serves it,
so no Node process runs in production. That lowers the urgency, but it does not remove the exposure.
Node runs every package's install scripts, in CI and in the image build, and receives no security fixes.

The move could not wait for its own turn any longer, because ADR-030 pins the Node version with
`engine-strict`. Pinning 20 would have made `npm ci` refuse to run on the maintainer's machine, which
runs Node 24.16.0. Every developer would then have to install an end-of-life runtime to keep working.

From the Node.js release schedule (`nodejs/Release`, `schedule.json`), as #166 tabulated it:

| Line | Active LTS from | Maintenance from | End of life |
|---|---|---|---|
| 20 | 2023-10-24 | 2024-10-22 | **2026-04-30** (passed) |
| 22 | 2024-10-29 | 2025-10-21 | 2027-04-30 |
| **24** | 2025-10-28 | 2026-10-20 | **2028-04-30** |
| 26 | 2026-10-28 | 2027-10-20 | 2029-04-30 |

## Decision

**The frontend toolchain runs on Node 24 LTS.**

- `.nvmrc` names the exact release that CI and the image build with: 24.21.0 at the time of writing.
- `engines` accepts `^24.16.0`, the 24 line from the oldest patch a maintainer runs.

Why 24, and not the neighbouring lines:

- **Not 22.** It is already in maintenance, and it ends about six months from now. Choosing it would
  mean a second migration almost at once.
- **Not 26.** It is not LTS until 2026-10-28. The packages here would be among the first to run on it,
  which is the risk ADR-004's jsdom story describes.
- **24:**
  - It is supported until 2028-04-30.
  - Every `engines` range in the current lockfile accepts it, including jsdom 29's
    `^20.19.0 || ^22.13.0 || >=24.0.0`.
  - Every frontend check passes on it: `npm ci`, lint, the colour check, the tests with coverage,
    the shipped-dependency audit and the production build.
  - The maintainer's machine already runs it, so local and CI agree from the first day.

This is the version decision only. ESLint 9 and the Vite and Vitest majors (#166 steps 3 and 4) stay
open.

## Consequences

- The build toolchain is back on a supported runtime, with about eighteen months of runway.
- **ADR-004's jsdom pin loses its reason.** That ADR pins jsdom to `^29` because "CI and the frontend
  Dockerfile both run Node 20". Neither does now, and jsdom 30's `^24.15.0` range admits Node 24. The
  pin itself stays until #166 step 4 moves the harness, because moving it means moving Vitest and Vite
  with it. ADR-004's choice of harness is unaffected.
- A developer on Node 20 or 22 is refused by `engine-strict` and has to install 24.

## Alternatives considered

- **Pin Node 20 first, move later.** Rejected. It pins an end-of-life runtime and makes `npm ci`
  refuse on the maintainer's machine for as long as the move takes.
- **Node 22.** Rejected: maintenance only, and end of life in April 2027.
- **Node 26.** Rejected: not yet LTS.
- **Leave `engines` out and pin only CI and the image.** Rejected. A developer machine on a different
  Node is exactly how ADR-004's jsdom failure stayed invisible.

## When to revisit

- **October 2027**, six months before Node 24's end of life. Move to 26.
- **A dependency requires Node 26.**
- **Dependabot's npm updater (#164) fails on `engine-strict`** because it resolves on a different Node.
  Then either align its Node or relax `engine-strict` for that job only, with a recorded reason.
