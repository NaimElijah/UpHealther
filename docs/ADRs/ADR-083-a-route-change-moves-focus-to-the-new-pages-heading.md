# ADR-083: A route change moves focus to the new page's heading

- **Status:** Accepted
- **Date:** 2026-10-31
- **Supersedes in part:** [ADR-022](ADR-022-the-phone-navigation-is-the-shared-dialog-not-a-drawer.md),
  whose *What this makes hard* says "the app manages no focus on a route change anywhere". The rest of
  ADR-022 stands.
- **Scope:** `frontend/src/router/RouteFocus.tsx`, `frontend/src/components/ui/PageHeading.tsx` and
  `pageHeadingFocus.ts`, and every page's `h1`. No new dependency.
- **Issue:** [#343](https://github.com/NaimElijah/UpHealther/issues/343), scan 09 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), finding X9.3, under the WCAG 2.2 AA target
  of [ADR-082](ADR-082-the-interface-targets-wcag-2-2-aa.md).

## Context

A single-page application changes pages without loading a document, so the browser does nothing to
focus when the route changes. Before this, the app did nothing either:

- **From the sidebar**, focus stayed on the link just followed, so a keyboard user had to tab through
  the whole shell again to reach the new page's content.
- **From the phone menu**, the menu's `Modal` closed and gave focus back to the menu button, as every
  dialog does (FR-40), so the user started from the top bar.
- **A screen reader announced nothing** of the page that had opened: no title, no heading.

ADR-022 recorded the phone menu's case and left the app-wide question to #104, before there was an
accessibility target. ADR-082 has since set WCAG 2.2 AA, under which this fails 2.4.3 Focus Order.

Two facts shape the answer. Most pages render a spinner and no heading until their data arrives, so the
heading is often not there in the commit that changed the route. And a user can move focus while a page
loads; a heading that arrives later must not take it back from them.

## Decision

**After the first render, a location change moves focus to the new page's `h1`.**

- **Every page's `h1` is `PageHeading`**, which carries `tabIndex={-1}`, so script can focus it and Tab
  skips it, and a marker attribute the router finds it by. `PageHeader` renders it, and the pages with a
  heading of their own (the dashboard, an upgrade, sign-in, registration) use it directly.
- **`RouteFocus`, mounted after the routes inside the router, makes the move.** It compares the
  location's `key` with the previous one, so Back to the first entry counts, and does nothing on the
  first render, where the browser's own starting point is right.
- **A heading that is not there yet takes the focus when it mounts**, but only if focus is still where
  it was when the route changed, or on `<body>`. Focus the user moved meanwhile stays where they put it.
- **The phone menu needs no special case.** Its `Modal` closes in the commit that changed the route, and
  hands focus back to the menu button in its cleanup. Every cleanup runs before any effect, so the
  router's move comes last.

## Consequences

**What this makes easy.**

- **A keyboard user starts on the new page**, and a screen reader announces its heading, from the
  sidebar, the phone menu, a link in a page or the browser's Back button alike.
- **A new page gets the behaviour by rendering its heading with `PageHeader` or `PageHeading`.**
  `RouteFocus.test.tsx` pins the move, the phone menu's case, the late heading and the user who moved
  focus first.

**What this makes hard.**

- **A page without a `PageHeading` keeps focus where it was**, silently. Nothing fails when a new page
  draws its `h1` by hand; a review has to notice.
- **Focus moves on every location change, a redirect included.** A redirect on the first load, such as
  `/` to `/dashboard`, therefore focuses the dashboard's heading once it has loaded, which is harmless:
  focus was on `<body>`.
- **Where focus goes after an action that removes a control without changing the route** is not this
  record's. Each page decides it, beside the action (finding X9.4).

## Alternatives considered

- **Leave focus alone, as before.** Rejected: it fails 2.4.3 under the target ADR-082 sets, and it is
  what made the phone menu hand focus to its own button after the user had chosen a page.
- **Focus `<main>`, or the page's container, instead of its heading.** Rejected: a screen reader then
  announces a region, or nothing, rather than what the page is. The heading says where the user has
  arrived.
- **Announce the new page through a live region, and leave focus where it was.** Rejected as the whole
  answer: it tells a screen reader user where they are, but leaves every keyboard user tabbing through
  the shell. The heading's focus does both.
- **A skip link instead.** Not rejected but not enough alone: it saves the tabbing, but only if the user
  thinks to use it after every route change, and it announces nothing.

## When to revisit

- **A page needs focus somewhere other than its heading on arrival**, such as a form whose first field
  should take it. That page then opts out, and this record names how.
- **React Router, or the platform, starts managing focus on navigation** (the Navigation API's focus
  reset, say). The app's own move would then compete with it.
