# ADR-066: A tab keeps nothing of a session that ended, after Back or when the server ended it

- **Status:** Accepted
- **Date:** 2026-10-30
- **Scope:**
  - `frontend/` — `api/session.ts` (a `pageshow` listener, and `onSessionEnd`), `lib/queryClient.ts`
    (the cache it builds registers itself), `main.tsx` (the root it mounts registers its unmount), and
    `api/notificationSocket.ts` (the session-ended ERROR frame ends the session).
  - NFR-35 in `docs/requirements/requirements.md`.
  - No backend change, no new dependency.
- **Issue:** [#339](https://github.com/NaimElijah/UpHealther/issues/339), findings S5.D1 and S5.D2; the
  owner decided S5.D2. It extends
  [ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md), whose sign-out ends with
  a full document navigation, and supersedes in part
  [ADR-054](ADR-054-a-session-that-ends-closes-its-open-sockets.md): its acceptance that "a tab whose
  session ended is not sent to the sign-in page", and the alternative it set aside, "send the client to
  the sign-in page on the ERROR frame". ADR-054's other decisions stand.

## Context

A session ends in the tab through `endSession()`, which drops the access token and navigates to
`/login`. The navigation was meant to discard everything the previous user's session held: the query
cache, whose entries live five minutes, and every provider's state. Two cases escaped it.

**The back/forward cache (S5.D1).** A browser may keep the page being left, frozen with its whole script
state, and restore it on Back without loading anything. On a shared machine:

1. A signs out.
2. B presses Back, and gets A's page, cache included.
3. B signs in on it, and presses Back again inside the SPA. A's upgrade renders from the cache.

That the browsers keep this page is inferred from their documented eligibility rules, not observed:
Playwright disables the cache by default, and nothing in the suite has run with it on.

**A window whose session ended elsewhere (S5.D2).** A sign-out in another tab of the same session, or
an administrator switching the account off, ends the session on the server. Since ADR-054, the server
then closes the session's open sockets with an ERROR frame whose message is `Session ended`. The
window that received it only dropped its access token, and went on showing that session's data until
its next HTTP call. ADR-054 accepted that, as what a tab whose session expired already did.

The query cache is built in `lib/`, and `api/` may not import `lib/` (ADR-061), so `endSession` cannot
reach it directly.

## Decision

- **Every restore reloads.** `api/session.ts` registers a `pageshow` listener when it loads. When the
  event's `persisted` is true, the page was restored from the back/forward cache, and it reloads. A
  fresh load starts again from the refresh cookie, as any load does.
- **A session's end empties the cache and unmounts the application first.** `api/session.ts` exports
  `onSessionEnd(listener)`, and `endSession` calls every registered listener after dropping the token
  and before navigating. `createQueryClient()` registers `clear()` for the client it builds, and
  `main.tsx` registers `root.unmount()` for the root it renders into.
  - The cache alone is not enough. Emptying it leaves the rendered page in place, so a page the
    back/forward cache kept would still show the previous user's data until its reload commits. And a
    query still mounted would fetch again on its next render, with no token: a 401, then a refused,
    audited refresh, then `endSession` a second time.
  - Unmounting synchronously is safe because every caller of `endSession` runs outside React's render
    and commit: `AuthContext`'s sign-out after an await, the response interceptor, and the STOMP
    client's ERROR callback.
  - So a restored page shows nothing and holds no server state even before its reload, and a restore
    that some browser fails to report is still empty.
- **The session-ended frame ends the session in the window at once.** `api/notificationSocket.ts`
  calls `endSession()` on it, instead of only dropping the token. Any other ERROR frame still leaves the
  session alone: Spring sends one generic frame for every refusal, an outage included. A renewal that
  fails is a different event, which #377 tracks, and this changes nothing about it: the server sends
  `Session ended` only when it ended the session itself.

## Consequences

**Easy.** A restored page shows nothing and holds no server state even before its reload, whichever
way its session ended. A window learns its session is over from the server as soon as the server
knows, with no request of its own. The layering is untouched: `lib/` and `main.tsx` register with
`api/`, the direction they already import in.

**Hard.**
- Back to the SPA from another site, or from the login page a sign-out left on, is a reload, not the
  instant restore the cache would have given. The session survives it, through the refresh cookie.
- Every client `createQueryClient` builds stays registered for the life of the page: the app builds
  one, and a test file that builds several clears them all at an `endSession`.
- Between a session's end and the sign-in page loading, the tab is blank. And `endSession` must never
  be called from React's render or commit: React cannot finish a synchronous unmount there, and says so
  only in a console error.
- A window is now sent to the sign-in page while the user may be typing in it. A draft in an open
  dialog is lost, where before it was lost at the next save instead, which the server refused.
- A window whose socket was down when the session ended never receives the frame, and still learns at
  its next HTTP call (ADR-054's other accepted gap).

**Unverified.** No test runs a browser with the back/forward cache on. An end-to-end test would launch
Chromium without the `--disable-back-forward-cache` switch Playwright adds (`ignoreDefaultArgs`), sign
in, open an upgrade, sign out, go back, and check that the upgrade's title is not shown. Whether
Chromium then keeps the page at all depends on its eligibility rules, an open WebSocket among them,
so the test would first have to show that the restore happens. The session-ended frame's effect would
need two pages of one browser context: sign out in one, and see the other reach `/login`.

## Alternatives considered

- **Reload only after a session ended in this page.** A flag set by `endSession` would keep the instant
  restore for every other Back. Rejected: a session can also end in another tab or on the server, and a
  page restored after that resumes with a token and a cache nothing has checked. One rule for every
  restore is simpler to reason about than a list of the ways a session ends.
- **`Cache-Control: no-store` on `index.html`.** It keeps a page out of the cache only as long as each
  browser's eligibility rules say so, and those rules are the browser's to change, so it would be a
  promise this repository cannot check. It also lives in `nginx.conf`, which `npm run dev` does not
  serve.
- **An `unload` listener**, which makes a page ineligible. Rejected: browsers are removing `unload`,
  and it would also cost every other navigation away.
- **Building the query client in `api/`**, so `endSession` could clear it. Rejected: it moves a
  React-facing cache under the transport layer to save one registration call.
- **On the session-ended frame, clear the cache and stay on the page.** Rejected: every provider would
  still hold the ended session's user and `isAuthenticated: true`, and the next action would fail with
  a refusal the user did not cause. `endSession` is the one way a session ends in a tab, and this is one.
- **Keep ADR-054's behaviour**, and leave the window as it was until its next HTTP call. Rejected: the
  owner decided, on S5.D2, that a window must not go on showing the data of a session that has ended.
