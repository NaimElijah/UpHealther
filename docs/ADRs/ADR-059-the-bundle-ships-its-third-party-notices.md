# ADR-059: The bundle ships its third-party notices

- **Status:** Accepted — extended by
  [ADR-071](ADR-071-registration-refuses-the-most-common-passwords.md), under which the jar carries the
  common-password list's notice
- **Date:** 2026-10-29
- **Scope:** `frontend/vite.config.ts` and the bundle it builds, which `frontend/Dockerfile` copies
  into the nginx image. Also records, for the jar and both images, why they need no build change
  today. NFR-57 in `docs/requirements/requirements.md`. The test that reads the served file joins the
  smoke suite of [ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md).
- **Issue:** [#337](https://github.com/NaimElijah/UpHealther/issues/337), scan 03 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), findings D3.F1 and D3.B5. Builds on
  [ADR-003](ADR-003-proprietary-source-available-licensing.md).

## Context

[ADR-003](ADR-003-proprietary-source-available-licensing.md) and `LICENSE` §5 leave every third-party
component under its author's terms. Those terms are obligations on the author as well. The MIT licence
requires its copyright and permission notice to be "included in all copies or substantial portions" of
the software, and Apache-2.0 §4 requires a copy of the licence, and any `NOTICE` text, to travel with
every redistribution. Nothing in the repository recorded how each shipped artifact meets them, and
scan 03 found that the bundle did not.

**The bundle.** The SPA's JavaScript, which every visitor's browser downloads, contains ten npm
packages. Nine are MIT: `react`, `react-dom`, `scheduler`, `react-router`, `react-router-dom`,
`@remix-run/router`, `@tanstack/react-query`, `@tanstack/query-core` and `axios`. One,
`@stomp/stompjs`, is Apache-2.0. The stylesheet carries Tailwind CSS 3's preflight (MIT), which
Tailwind marks with a `/*! … MIT License */` banner. The minifier removes comments, and Lightning CSS
strips that banner too. The preflight itself derives from normalize.css and modern-normalize, whose
holders Tailwind's `lib/css/LICENSE` names. The shipped `dist/` held no notice of any kind. Once the
planned AWS deployment ([#198](https://github.com/NaimElijah/UpHealther/issues/198)) serves the SPA,
each visitor receives those copies without the notices their licences require.

**The jar.** `./mvnw package` builds a boot jar that nests 120 dependency jars, each kept whole, with
its own `META-INF/LICENSE` and `NOTICE`. This was checked on `tomcat-embed-core-11.0.27.jar` and
`postgresql-42.7.13.jar`. Lombok and every test-scope jar are left out.

**The images.** No CI step pushes an image, and the planned deployment pushes them only to the
author's own registry, which is the author's own hosting and not distribution to a third party. The
backend image's base, `eclipse-temurin` JRE on Alpine, carries the JDK (GPL-2.0 with the Classpath
Exception), busybox (GPL-2.0) and musl (MIT). The frontend's base is `nginx` on Alpine.

**What Vite offers.** Vite 8 has a `build.license` option that writes the name, version, licence
identifier and full licence text of every package bundled into a JS chunk to a file in `dist/`. A
trial build listed exactly the ten packages. It reads JS chunks only, so it cannot see the
stylesheet. Its plugin also skips virtual (`\0`) modules.

## Decision

**The build writes `THIRD-PARTY-NOTICES.txt` into `dist/`, nginx serves it at
`/THIRD-PARTY-NOTICES.txt`, and a smoke test fetches it from the composed stack.**

- **Vite writes it.** `frontend/vite.config.ts` sets `build.license` with
  `fileName: 'THIRD-PARTY-NOTICES.txt'`. Every package that enters a JS chunk is listed with its
  licence text, so a new runtime dependency's notice ships without anyone remembering to add it.
- **A small plugin adds Tailwind.** It appends two texts to the same file, in the same
  `## name - version (licence)` shape: the package's `LICENSE`, which is Tailwind Labs', and
  `lib/css/LICENSE`, the licence of the preflight. The second is the one that matters, because the
  preflight is the code that ships in the stylesheet, and it names the five holders of the work it
  derives from. Its `generateBundle` hook runs with `order: 'post'`, so it sees the file Vite emitted.
  It fails the build when the file or either text is missing, so the bundle cannot ship without
  them.
- **nginx serves it as it serves any file in `dist/`.** `frontend/Dockerfile` already copies the
  whole directory, so the file answers as `text/plain`. The image is unchanged. `nginx.conf` gains
  only `charset utf-8;` in `location /`, so the verbatim licence texts are read as UTF-8.
- **The smoke suite checks the served file.** A second test in `e2e/tests/smoke.test.ts`
  ([ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md)), which
  `npm run test:images` and CI's images job run, fetches it from the composed stack and asserts a
  `text/plain; charset=utf-8` response, a section for every package in `frontend/package.json`'s
  `dependencies` and for `tailwindcss`, and a non-empty body in each section.
- **A Vitest test checks the build's side.** `frontend/src/test/thirdPartyNotices.test.ts` checks that
  the notices the build writes carry both of Tailwind's licence texts.
- **The listing leaves out two things, and that is a judgment.** Vite's modulepreload polyfill and
  Rolldown's runtime helpers are a few lines of tool-generated glue, which Vite's plugin skips as
  virtual modules. Their licences are not listed. We judge those lines too small and too generic to
  be "substantial portions" of anything. It has not been reviewed by a lawyer, and nothing in the
  build would show a different reading.
- **The jar needs nothing.** It meets its obligations by keeping each nested jar whole, with its own
  `LICENSE` and `NOTICE`. The build does not change.
- **The images need nothing today,** because neither is distributed.
- **No dependency is added.**

## Consequences

**What this makes easy**

- A visitor, or anyone auditing the deployment, finds every notice at one fixed URL.
- A dependency bump or a new runtime dependency updates the file with no manual step, and the smoke
  test fails if a package in `dependencies` has no section.
- The obligations of each artifact are written down, along with the event that changes them.

**What this makes hard**

- **The smoke test checks that each package has a section with some text, not that the text is the
  right licence.** An empty section fails. A section with the wrong text passes. For Tailwind, the
  Vitest test pins both texts.
- **Anything taken from a package outside a JS chunk is invisible to Vite's plugin.** Tailwind's
  preflight is the one case today, and it needs the hand-written plugin. A stylesheet, font or image
  copied from another package must be added the same way. A package in `dependencies` that never
  reaches a JS chunk, a CSS-only one say, fails the smoke test, because Vite writes no section for it.
  An asset taken from a package that is not in `dependencies` is not detected.
- **Vite's plugin collects `LICENSE`, `LICENCE` and `COPYING` files, and never a `NOTICE`.**
  Apache-2.0 §4(d) requires a work's `NOTICE` text to travel with it. `@stomp/stompjs` 7.5.0, the one
  Apache-2.0 package bundled, ships none today.
- **The Tailwind plugin reads `tailwindcss/package.json`, the `LICENSE` beside it and
  `lib/css/LICENSE`.** A Tailwind release that moves any of them fails the build, which is the
  intended loudness, and the fix is in the plugin.
- **The tool-generated glue is unlisted** (see the Decision). It is a risk accepted, not a finding
  closed.

**Neutral**

- nginx serves the file as UTF-8, because licence texts are copied verbatim and may hold
  characters outside ASCII. The smoke test asserts the charset.
- Nothing links to the file from the interface. A visitor has to know the URL, or read the README.
- No backend code, dependency or runtime behaviour changes.

## Alternatives considered

- **`rollup-plugin-license`, or a similar plugin.** Rejected: a new dependency, with its own
  maintenance and supply-chain cost, for what Vite 8 now does itself. **Revisit if** Vite drops
  `build.license`, or the plugin's output is needed in a form Vite's cannot take.
- **A hand-maintained notices file.** Rejected: it drifts silently on every dependency change, and
  nothing would fail when it did. The generated file and its test cannot drift that way.
- **Keeping the minifier's legal comments (Rolldown's `legalComments`).** Rejected: only React
  carries `@license` comments, so axios, TanStack Query and stompjs would still ship without
  notices. Lightning CSS strips Tailwind's banner as well. **Revisit if** the packages the bundle
  holds all carry their notices in a comment.
- **A link to the file from the interface.** Deferred, not rejected. The place for it is a legal page,
  and [#222](https://github.com/NaimElijah/UpHealther/issues/222), the issue for a privacy notice and
  terms of use, is where that page will live.
- **An SBOM or a licence report for the backend now.** Rejected for now: nothing is distributed, so
  nothing obliges one. This is the trigger below.

## When to revisit

- **An image or a jar leaves the author's own hosting,** for example an image pushed to a registry
  others pull from, or a jar handed to a third party. That needs a notices file for the jar and the
  images, and a written source offer for busybox's GPL-2.0.
- **Tailwind 4 lands** ([#369](https://github.com/NaimElijah/UpHealther/issues/369)), because it
  changes how the stylesheet is built, through a plugin package of its own. Check that the preflight
  still comes from `tailwindcss`, and that the plugin still names the package it does.
- **A stylesheet or asset from another package enters the bundle,** because Vite's plugin cannot see
  it.
- **An Apache-2.0 runtime dependency that ships a `NOTICE` file enters the bundle.** Vite's plugin
  does not collect it, so its text must be added the way Tailwind's licences are.
- **Vite changes or drops the `license` option.**
