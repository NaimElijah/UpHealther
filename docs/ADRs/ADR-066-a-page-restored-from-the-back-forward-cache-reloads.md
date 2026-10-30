# ADR-066: A page restored from the back/forward cache reloads, and a session's end empties the query cache

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `frontend/` — `api/session.ts` (a `pageshow` listener, and `onSessionEnd`) and
    `lib/queryClient.ts` (the cache it builds registers itself).
  - NFR-35 in `docs/requirements/requirements.md`.
  - No new dependency.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), finding S5.D1. It extends
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), whose sign-out ends with
  a full document navigation.

## Context

A session ends in the tab through `endSession()`, which drops the access token and navigates to
`/login`. The navigation was meant to discard everything the previous user's session held: the query
cache, whose entries live five minutes, and every provider's state.

A browser's back/forward cache breaks that. It may keep the page being left, frozen with its whole
script state, and restore it on Back without loading anything. On a shared machine:

1. A signs out.
2. B presses Back, and gets A's page, cache included.
3. B signs in on it, and presses Back again inside the SPA. A's upgrade renders from the cache.

That the browsers keep this page is inferred from their documented eligibility rules, not observed:
Playwright disables the cache by default, and nothing in the suite has run with it on.

The query cache is built in `lib/`, and `api/` may not import `lib/` (ADR-061), so `endSession` cannot
reach it directly.

## Decision

- **Every restore reloads.** `api/session.ts` registers a `pageshow` listener when it loads. When the
  event's `persisted` is true, the page was restored from the back/forward cache, and it reloads. A
  fresh load starts again from the refresh cookie, as any load does.
- **A session's end empties the cache first.** `api/session.ts` exports `onSessionEnd(listener)`, and
  `endSession` calls every registered listener after dropping the token and before navigating.
  `createQueryClient()` registers `clear()` for the client it builds. So a restored page holds no
  server state even before its reload, and a restore that some browser fails to report is still
  empty.

## Consequences

**Easy.** Nothing a restored page shows has outlived the session it belonged to, whichever way that
session ended, in this tab or elsewhere. The layering is untouched: `lib/` registers with `api/`, the
direction it already imports in.

**Hard.** Back to the SPA from another site, or from the login page a sign-out left on, is a reload,
not the instant restore the cache would have given. The session survives it, through the refresh
cookie. Every client `createQueryClient` builds stays registered for the life of the page: the app
builds one, and a test file that builds several clears them all at an `endSession`.

**Unverified.** No test runs a browser with the back/forward cache on. An end-to-end test would launch
Chromium without the `--disable-back-forward-cache` switch Playwright adds (`ignoreDefaultArgs`), sign
in, open an upgrade, sign out, go back, and check that the upgrade's title is not shown. Whether
Chromium then keeps the page at all depends on its eligibility rules, an open WebSocket among them,
so the test would first have to show that the restore happens.

## Alternatives considered

- **Reload only after a session ended in this page.** A flag set by `endSession` would keep the instant
  restore for every other Back. Rejected: a session can also end in another tab or on the server
  (ADR-054), and a page restored after that resumes with a token and a cache nothing has checked. One
  rule for every restore is simpler to reason about than a list of the ways a session ends.
- **`Cache-Control: no-store` on `index.html`.** It keeps a page out of the cache only as long as each
  browser's eligibility rules say so, and those rules are the browser's to change, so it would be a
  promise this repository cannot check.
  It also lives in `nginx.conf`, which `npm run dev` does not serve.
- **An `unload` listener**, which makes a page ineligible. Rejected: browsers are removing `unload`,
  and it would also cost every other navigation away.
- **Building the query client in `api/`**, so `endSession` could clear it. Rejected: it moves a
  React-facing cache under the transport layer to save one registration call.
