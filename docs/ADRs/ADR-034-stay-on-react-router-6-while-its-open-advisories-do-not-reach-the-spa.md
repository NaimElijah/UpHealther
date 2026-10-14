# ADR-034: Stay on React Router 6 while its open advisories do not reach the SPA

- **Status:** Accepted
- **Date:** 2026-10-14
- **Scope:** the `react-router-dom` dependency of `frontend/`, and how its advisories are weighed. It
  leaves the audit gate that [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)
  set (`npm audit --omit=dev --audit-level=high`) as it is.
- **Issue:** [#237](https://github.com/NaimElijah/UpHealther/issues/237)

## Context

The SPA shipped `react-router-dom` 6.30.4. GitHub's advisory database lists three moderate advisories
against it. CI's gate stops only at `high`, so none of them failed a build, and nothing recorded them.

| Advisory | Affected | Fixed in |
|---|---|---|
| [GHSA-jjmj-jmhj-qwj2](https://github.com/advisories/GHSA-jjmj-jmhj-qwj2): open redirect leading to XSS | `react-router-dom` 6.30.2 to 6.30.5 | 6.30.6 |
| [GHSA-wrjc-x8rr-h8h6](https://github.com/advisories/GHSA-wrjc-x8rr-h8h6): open redirect through a backslash in `<Link>` and `useNavigate`, a bypass of CVE-2025-68470 | `react-router` 6.0.0 up to 7.18.0 | 7.18.0 |
| [GHSA-337j-9hxr-rhxg](https://github.com/advisories/GHSA-337j-9hxr-rhxg): constructor injection through `deserializeErrors()` during SSR hydration | `react-router` 6.4.0 up to 7.18.0 | 7.18.0 |

The first is fixed inside the 6.x line. The other two are fixed only in 7.18. That is a major upgrade,
and 8.x is already the current major. #166 lists React Router 7 under "explicitly not proposed",
because it changes application code rather than tooling.

**What the code exposes to each one:**

- **GHSA-337j needs SSR hydration in Framework or Data Mode.** Its advisory says Declarative Mode is not
  affected. `src/router/index.tsx` is `<BrowserRouter>` with `<Routes>`, which is Declarative Mode.
  There is no data router and no `hydrateRoot`. The SPA is a static bundle that nginx serves, and
  nothing renders on a server.
- **GHSA-wrjc needs an attacker-supplied path passed to a navigation call.** No navigation target in
  `src/` is built from input:
  - Every `<Link>`, `<Navigate>` and `navigate()` target is a literal, or comes from the constant list
    in `src/components/layout/navItems.ts`.
  - The exceptions are the `/upgrades/${id}` targets: `upgrade.id` in `UpgradeCard.tsx`, and a
    notification's `relatedUpgradeId` in the notification list, dropdown and toast. Each id is a UUID
    the server generated. Each target starts with the fixed `/upgrades/` segment, so no value can make
    it external.
  - `navigate(-1)` takes no path.
  - No redirect target is read from the URL, the query string or `location.state`.

## Decision

**Stay on React Router 6, at 6.30.6.**

- `package.json` asks for `^6.30.6`, not `^6.22.0`, so a fresh resolve cannot fall below the fix.
- That closes GHSA-jjmj. 6.30.6 also brings `@remix-run/router` 1.23.4.
- GHSA-wrjc and GHSA-337j are recorded here as **not reaching this code**, for the reasons above.
  `npm audit --omit=dev` still lists both against `react-router`. That listing is expected, and this
  record is the explanation.

## Consequences

- The one advisory that reached this code is closed without changing any application code.
- **`npm audit --omit=dev` is not clean.** Two moderate advisories stay in its report until the next
  major move. Anyone reading it needs this record to tell them apart from new ones. The CI gate still
  passes, because both are moderate.
- **The "not reaching" judgement holds only while the code stays that way.** A navigation target built
  from input, such as returning to the page a user came from after login, brings GHSA-wrjc in. The
  revisit triggers below name that.
- **The 6.x line is in maintenance.** 6.30.6 shows it still gets security fixes, but two current
  advisories already have no 6.x fix.

## Alternatives considered

- **React Router 7.18, or 8.** Rejected for now. Either closes all three advisories, but each is a
  major upgrade of the router that every page runs on. It needs checking route by route, and it would
  close only two advisories, neither of which reaches this code. 7 is also no longer the current major.
  #166 already scoped this out, as a change to application code that needs its own decision.
- **Lower the CI gate to `moderate`.** Rejected. ADR-002 and #59 chose `high`. A `moderate` gate would
  turn red today on two advisories that do not apply, and the only way back to green is a major
  upgrade. A gate that fails on findings already judged not to apply gets ignored or switched off.
- **Leave 6.30.4 and record all three.** Rejected. GHSA-jjmj has a fix inside the line the project
  already runs, and the bump changes nothing else.

## When to revisit

- **A navigation target is built from input**, for example a redirect-after-login, a `returnTo`
  parameter, or a link taken from server-supplied text. Move to 7.18 or later first, or validate the
  target.
- **The 6.x line stops receiving fixes**, or an advisory that reaches this code is fixed only in 7 or
  later.
- **The SPA adopts a data router or server rendering.** GHSA-337j applies then.
