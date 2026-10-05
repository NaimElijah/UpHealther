# ADR-022: The phone navigation is the shared dialog, not a drawer

- **Status:** Accepted
- **Date:** 2026-10-06
- **Scope:** `frontend/src/components/layout/` — `NavMenu`, `NavLinks`, `navItems`, and the brand in
  `Navbar`. Builds on [ADR-013](ADR-013-trapping-focus-without-a-native-dialog.md); no new dependency.
- **Issue:** [#97](https://github.com/NaimElijah/UpHealther/issues/97), and the review of #135

## Context

The sidebar is the only navigation, and it is `hidden md:block`. Below 768px nothing replaced it, so
four pages — health areas, active upgrades, progress history and account administration — were linked
from nowhere on a phone (FR-6, FR-21, FR-42).

Whatever replaces it on a narrow window is an overlay over the page, and an overlay that claims to be
modal has to do everything ADR-013 lists: move focus in, confine Tab, mark the page behind `inert`,
close on Escape and hand focus back. The app has exactly one component that does all of that and has
tests proving it: `Modal`. Its limit is its shape — a centred panel of at most `max-w-lg`, with a title
and a close button.

The navbar has little room to give. At 320px the menu button, the three-way theme switch, the
notification bell and Logout take the whole bar.

## Decision

**The phone navigation is a button in the navbar that opens the shared `Modal`, titled "Menu", holding
the same links as the sidebar.**

- **One list, two renderings.** The entries and the administrator rule live in `navItems.ts`, the links
  in `NavLinks`, and both the sidebar and the menu render `NavLinks`, so neither can offer a page the
  other does not. The links are a navigation landmark named "Main", because the navbar is a `<nav>` too.
- **The button is `md:hidden`, the sidebar `hidden md:block`**, so exactly one of the two is on screen
  at any width.
- **The menu closes when this tab's location changes**, however it changed: one of its links, the
  browser's Back button, or anything else that navigates. `Layout` stays mounted across routes, so the
  menu has to react to the location rather than to its own clicks. A Ctrl- or Cmd-click opens another
  tab and moves nothing here, so it leaves the menu open.
- **It also closes once the window is wide enough to show the sidebar**, through a
  `(min-width: 768px)` media query that restates Tailwind's `md`. Otherwise a menu left open across the
  breakpoint (a tablet rotated to landscape) would sit beside the sidebar, with the page still inert and
  its own button gone.
- **Below 360px the brand is not rendered.** The bar's controls leave it no room, a link clipped to
  nothing would still be a focusable tab stop with no visible focus ring, and the menu's Dashboard
  entry stands in for its home link.

## Consequences

**What this makes easy**

- **No second overlay to get right.** Focus handling, `inert`, Escape and focus restore are ADR-013's,
  already tested, and FR-40 holds for the menu without new code.
- **Adding a page means one edit** to `navItems.ts`, and it appears in both navigations.

**What this makes hard, or leaves open**

- **It is a centred panel, not an edge drawer.** It does not slide in from where the button is, and it
  is bounded by the modal's width. With nine entries it fits a 320px window with room to spare.
- **After a link is followed, focus returns to the menu button**, as it does when any dialog closes,
  rather than to the page the user chose. That matches the sidebar, where focus stays on the clicked
  link: the app manages no focus on a route change anywhere. Moving focus to the new page's heading is
  an app-wide question for [#104](https://github.com/NaimElijah/UpHealther/issues/104), not something
  to settle for the phone alone.
- **Two widths are restated by hand.** The 768px query must move with Tailwind's `md`, and the 360px
  brand breakpoint was measured against the bar's current controls. Nothing checks either
  automatically: jsdom has no layout, which is the gap #61 tracks. Both were verified in a browser.

## Alternatives considered

- **A slide-in drawer.** Rejected. It would need its own focus trap, inert handling, Escape and focus
  restore, which is everything ADR-013 built, written again for a panel of a different shape. Those are
  the parts that are easy to get subtly wrong and hard to test in jsdom, and the gain is the animation
  and the edge it slides from.
- **A bottom tab bar.** Rejected. Eight entries, nine for an administrator, do not fit a phone's tab
  bar. It would need a "more" overflow, which is a menu again, plus padding on every page so the bar
  never covers content (NFR-20).
- **Linking the missing pages from the dashboard.** This is what the sidebar's old comment claimed. It
  was rejected because it makes the dashboard the navigation, and every page reachable only through it
  is two taps and a scroll away.
- **A native `<dialog>` for the menu alone.** Rejected for ADR-013's reasons: one dialog mechanism in
  the app, not two.

## When to revisit

- **The navigation outgrows a dialog**, say past a dozen entries or with nested sections. A drawer with
  grouped sections then earns its own overlay code.
- **A page needs the navigation on screen beside its content on a phone**, such as a persistent tab
  bar for a two-page workflow. That is a different requirement from reaching every page.
- **Native `<dialog>` replaces the hand-built trap** (ADR-013's own revisit trigger). The menu moves
  with `Modal`, and a drawer could then be a styled `<dialog>` rather than new trap code.
- **The navbar's controls change width** — a fourth control, or a narrower theme switch. Re-measure the
  360px brand breakpoint.
