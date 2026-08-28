# ADR-013: Trap focus with a portal and `inert`, not with a native `<dialog>`

- **Status:** Accepted
- **Date:** 2026-08-28
- **Supersedes in part:** [ADR-005](ADR-005-one-page-width-and-a-shell-that-cannot-overflow.md), whose
  "the dialog announces itself but does not contain anything" section recorded the gap this closes
- **Scope:** `frontend/src/components/ui/Modal.tsx` and its tests. No new dependency.

## Context

`Modal` had `role="dialog"` and an accessible name, and deliberately no `aria-modal`. Its own doc
comment explained why: nothing trapped the keyboard, so Tab walked out behind the scrim, and focus was
neither moved in on open nor restored on close. Claiming `aria-modal` would have told a screen reader
that the page behind was inert while focus could still reach it — controls that are focusable but no
longer announced, which is worse than the plain role. ADR-005 recorded that as an open question and
named a native `<dialog>` with `showModal()` as the correct answer. §6 of the requirements carried the
same entry.

Two forces shaped the answer, and neither is about the trap itself.

**The overlay is not a portal.** It is `fixed inset-0` inside the page tree, so "everything behind the
dialog" is an ancestor's siblings at half a dozen different depths rather than one list of nodes.
Anything that marks the page behind has either to walk the whole ancestor chain, writing attributes
into DOM owned by components that know nothing about it, or to move the dialog somewhere the walk is
one level deep.

**`aria-hidden` and `inert` are not interchangeable in this repo.** `@testing-library/dom` treats an
`aria-hidden` ancestor as non-existent for role queries, and knows nothing about `inert` — verified in
its `role-helpers.js`. No test breaks today, because no page test currently opens a modal. The next one
to do so would find it could not query the page it was standing on.

## Decision

Keep `<div role="dialog">` and build the trap by hand, in three parts.

**Portal the overlay to `document.body`.** "The page behind" becomes exactly "the other children of
`<body>`", which is a one-level walk. This is the codebase's first portal.

**Mark those children `inert`, refcounted.** `inert` removes them from the accessibility tree and from
the pointer, not only from the keyboard, and it is invisible to Testing Library's queries. The refcount
is what makes two open dialogs safe — without it, closing the second releases the page while the first
is still up. Overlays skip each other by a `data-modal-overlay` attribute set **in the JSX**, not by a
registry populated from an effect: React inserts both portals during the mutation phase and runs
neither effect until afterwards, so an effect-time registry loses that race and the first dialog marks
the second one inert.

**Confine Tab with an `onKeyDown` on the dialog**, not a listener on `document`, so two open dialogs
cannot fight over the key. `preventDefault` fires at the two boundaries only; in the middle the
browser's own order is better than a flat `querySelectorAll` list, which gets radio groups wrong.

`aria-modal="true"` is then set, because all three make it true.

## Consequences

**What this makes easy.** The dialog is now honest about what it is, and FR-40 can state the whole
contract. The portal also removes a latent fragility: `position: fixed` resolves against the viewport
only while no ancestor has a `transform`, `filter` or `will-change`, which was true by luck rather than
by design.

**What this makes hard.** Focus restoration depends on the element that opened the dialog still being
in the document; on the health-areas delete path it is not, and focus falls back to `<body>`. Fixing
that properly means a `restoreFocusTo` prop, which one call site does not justify. `ToastContainer`
lives inside `#root`, so a toast raised while a dialog is open goes inert with the rest of the page —
still painted above it at `z-[60]`, no longer dismissable. That is what `aria-modal` means, and it is
filed rather than folded in here.

**What cannot be verified.** jsdom implements `inert` not at all, so the tests pin that the attribute
is set and cleared on the right nodes and stop there. That a browser honours it belongs to the gap §6
already records for contrast and layout: closing it needs a real browser in CI, which
[ADR-004](ADR-004-frontend-test-harness.md) deferred. The same harness moves focus on Tab for nobody,
so every Tab test asserts `defaultPrevented` alongside the resulting `activeElement` — the first
because a browser would otherwise overwrite our move, the second because without it the test would pass
against no handler at all.

**Cost paid.** One existing test inverted (`…ThenItDoesNotClaimTheRestOfThePageIsInert`, which pinned
the omission this record closes) and one query re-anchored, because the backdrop test reached its
target through RTL's `container` and the portal empties it. Eight of the ten survived untouched.

## Alternatives considered

- **A native `<dialog>` with `showModal()`.** Still the platform's own answer, and still the one with
  the least code: a real trap, real inertness, the top layer and native Escape. Not taken here for the
  reason ADR-005 already records — the backdrop moves to `::backdrop` in `index.css`, which sits
  outside what `check:colours` scans — and for a second: jsdom's `<dialog>` support is partial, so the
  existing test file would be rewritten rather than extended. *Revisit when* `check:colours` is
  extended to the stylesheet, at which point this record should be superseded rather than amended.
- **`aria-hidden` instead of `inert`.** Rejected on the Testing Library consequence above. It is also
  the weaker guarantee — it hides from assistive technology and leaves the pointer and the keyboard
  alone. *Revisit:* never; `inert` is Baseline and has been since mid-2022.
- **No portal; walk the ancestor chain marking siblings at every level.** What the `aria-hidden` npm
  package does. Rejected: it writes attributes into DOM owned by half a dozen other components at
  arbitrary depths, and a React re-render replacing any one of those siblings silently drops the mark.
- **Focus the first control rather than the dialog container.** Rejected: it announces "Close modal,
  button" instead of the dialog's role and name, which is what the `useId`/`aria-labelledby` work
  exists to deliver. Focusing the container also makes the no-focusable-child case structurally
  impossible rather than a branch to defend.
- **Adding `@testing-library/user-event` for `userEvent.tab()`.** Rejected: it is not installed, and it
  simulates tab order in JavaScript rather than reproducing a browser's, so it is no closer to the real
  thing than firing the key. The `defaultPrevented` assertion covers the same contract without a new
  dependency. *Revisit when* a richer keyboard workflow needs more than boundary assertions.
- **Filtering the focusable list by visibility.** Rejected: jsdom reports every element as zero-sized,
  so the filter would return an empty list in every test and leave the trap untestable. It costs
  nothing today because the call sites conditionally render their optional fields rather than hiding
  them in CSS.
