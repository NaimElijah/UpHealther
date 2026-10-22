# ADR-038: The frontend is formatted by Prettier

- **Status:** Accepted — its scope extended to the root `package.json` and `scripts/` by
  [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md), with the same configuration;
  and to `e2e/`'s `package.json`, configuration and tests by
  [ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md)
- **Date:** 2026-10-15
- **Scope:** every file in `frontend/` that Prettier can parse, except those `frontend/.prettierignore`
  names; `frontend/prettier.config.js`; the `format` and `format:check` scripts; the frontend job in
  `.github/workflows/ci.yml`.
- **Issue:** [#161](https://github.com/NaimElijah/UpHealther/issues/161), the frontend half. The
  backend half is [ADR-037](ADR-037-the-backend-is-formatted-by-palantir-java-format-through-spotless.md).

## Context

The frontend had no formatter. ESLint is not one: ESLint 10's recommended set keeps a single rule about
layout, `no-unexpected-multiline`, and the project has deprecated the rest. Of the 86 rules active on
`src/main.tsx` (`eslint --print-config`), that is the only layout rule. `.editorconfig` fixes
indentation and line endings, and says nothing about quotes, wrapping or trailing commas.

Prettier 3.9.10 was run over an LF-normalised copy of `frontend` at `f002e14`: `src`, `scripts`, and
the root configuration files, 128 files in all. Each row changes only the options named. The rest are
Prettier's defaults, including `semi: true` and `trailingComma: "all"`.

| Options | Files changed | Lines added / removed |
|---|---|---|
| `printWidth: 100` (double quotes) | 124 | +5,518 / −3,732 |
| `singleQuote`, `printWidth: 80` | 103 | +5,090 / −1,468 |
| `singleQuote`, `printWidth: 100` | 73 | +2,637 / −851 |
| `singleQuote`, `printWidth: 100`, `trailingComma: "es5"` | 84 | +2,691 / −907 |
| **`singleQuote`, `printWidth: 120`** | **56** | **+1,598 / −685** |
| `singleQuote`, `printWidth: 120`, `trailingComma: "es5"` | 74 | +1,656 / −743 |
| `singleQuote`, `printWidth: 120`, `arrowParens: "avoid"` | 71 | +1,701 / −788 |
| `singleQuote`, `printWidth: 120`, `bracketSameLine` | 64 | +1,611 / −737 |

Each row is a single pass. Prettier is not always idempotent: on the chosen options, a second pass
joined one member chain in `UpgradeDetailsPage.test.tsx` that the first had kept broken, because the
source broke it. The reformat that landed is therefore the fixed point, at +1,598 / −687.

## Decision

**Format the frontend with Prettier 3.9.10, setting `singleQuote` and `printWidth: 120`. Every other
option is Prettier's default.**

- **The two options are the ones that moved the fewest lines.** The sources already used single quotes.
  A width of 120 keeps most lines whole, and it is also the backend's width (ADR-037), so the
  repository has one.
- **`npm run format`** rewrites the files, and **`npm run format:check`** fails on any file it would
  change. CI runs the check in the frontend job, before the lint, so a layout failure is reported as one
  rather than as a lint finding.
- **Prettier is pinned exactly,** at `3.9.10` rather than `^3.9.10`. Its patch releases can move lines,
  and a range would let one reformat code during an unrelated install. A bump is its own
  `style(frontend)` commit, and its SHA joins `.git-blame-ignore-revs`.
- **`.prettierignore` leaves five things alone,** each with its reason beside it:
  - `dist` and `coverage`, which are generated;
  - `package-lock.json`, which npm owns;
  - `*.md`, because prose is wrapped by hand;
  - `index.html`. Its inline boot script is hashed into the Content-Security-Policy (ADR-018). A
    reformat changes the hash, and the browser then refuses the script. `bootScriptCsp.test.ts` fails
    the build first.
- **The configuration is `prettier.config.js`,** not `.prettierrc.json`, so the reason for each option
  can sit beside it. That is how `eslint.config.js` is written too.
- **The one-time reformat is a commit of its own.** It contains only `npm run format`'s output, and
  `.git-blame-ignore-revs` names it.

**New dependency:** `prettier` 3.9.10, development only. It has no dependencies of its own, it is MIT,
and it is actively released: 3.9.10 is the latest. It replaces layout comments in review. ESLint cannot
take that job, because it has deprecated its layout rules.

## Consequences

- **Layout is no longer a review topic** in the frontend either.
- **`.js` and `.mjs` files are formatted, though they are still not linted.** ADR-035 left their linting
  to be revisited "with the formatter". It stays out of scope here, because it changes what lints, not
  how the code is laid out.
- **`index.html` keeps a hand-kept layout.** A change to it still has to update the hash in
  `nginx.conf`, as before.
- **An editor should run Prettier on save.** Without it, `npm run format` corrects whatever the editor
  did.

## Alternatives considered

- **`eslint-config-prettier`.** #161 proposed it to turn off ESLint rules that fight Prettier. It is not
  added. Under ESLint 10 the only such rule left is `no-unexpected-multiline`. That rule flags a line
  starting with `(` or `[` that would continue the previous statement, and it cannot fire on code
  Prettier wrote with semicolons. A dependency that switches off one rule which cannot conflict is
  weight without a job.
  - **Revisit:** if `semi: false` is ever chosen, or a stylistic ESLint plugin is added.
- **Double quotes, Prettier's default.** Rejected. At width 100 it changes 124 files and 9,250 lines,
  against 73 files and 3,488 lines with single quotes, because the sources use single quotes.
  - **Revisit:** never on its own.
- **Width 80, Prettier's default.** Rejected. With single quotes it changes 103 files and 6,558 lines,
  mostly by splitting lines that fit in 120.
  - **Revisit:** never on its own.
- **`printWidth: 100`.** Rejected. It changes 73 files instead of 56, and splits lines that read well
  at 120.
  - **Revisit:** if the code is read on narrower screens than today's.
- **`prettier-plugin-tailwindcss`** (class sorting). Not adopted. Sorting classes is a separate opinion,
  with its own churn.
  - **Revisit:** if class order becomes a review topic.
- **Formatting `index.html`, and updating the hash in the same commit.** Rejected. It would tie every
  Prettier bump to a security-header change.
  - **Revisit:** if the boot script moves out of `index.html`.

## When to revisit

- **A Prettier release moves more than a handful of lines.** Decide whether to take the bump, or hold
  the pin, in a commit of its own.
- **Prettier 4 is released.**
