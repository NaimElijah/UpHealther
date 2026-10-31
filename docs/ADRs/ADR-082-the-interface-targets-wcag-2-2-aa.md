# ADR-082: The interface targets WCAG 2.2 AA

- **Status:** Accepted
- **Date:** 2026-10-31
- **Scope:** `frontend/`: every page, component and context, `index.html` and the theme. No new
  dependency, and no backend change.
- **Issue:** [#104](https://github.com/NaimElijah/UpHealther/issues/104), where the owner decided the
  target, and [#343](https://github.com/NaimElijah/UpHealther/issues/343), scan 09 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), which verified the interface against it.

## Context

Until now the requirements stated two accessibility rules, and nothing else: a dialog's focus handling
(FR-40) and the theme's contrast (NFR-19). Everything else a person using a keyboard or a screen reader
meets was a defect against nothing.

- **The audit on #104** found an upgrade card whose only link to the upgrade's page was a clickable
  heading, a notification bell that did not close on Escape, toggle buttons that exposed no state,
  fields whose errors were not tied to them, and a spinner that said nothing. With no target, each was a
  question of taste rather than a fault.
- **Scan 09** reviewed the pages, the components, the contexts, the router, `index.html` and the theme
  against WCAG 2.2 AA and the WAI-ARIA Authoring Practices, in both themes and in a real browser. It
  verified twenty-seven failures: one blocker, thirteen major, nine minor and four nits. The blocker was
  the card: forty-five presses of Tab on the dashboard never reached an upgrade, so its page, and every
  edit, reminder, reflection and progress entry on it, could not be reached without a pointer.

A review needs a standard to review against, and a fix needs one to be done against. Without one, the
next scan would find the same kinds of failure again and have nothing to call them.

## Decision

**The interface meets WCAG 2.2 at level AA.**

- **Every success criterion at levels A and AA applies**, to every page, in both themes and at every
  window width from 320px, which NFR-20 already sets as the narrowest.
- **The WAI-ARIA Authoring Practices are the pattern reference** for a widget the standard leaves open:
  a disclosure, a radio group, a dialog. A control built differently from its pattern says why beside it.
- **NFR-61 states the requirement**, under §2.7 of the requirements, and names the tests that enforce
  what jsdom can observe. A failure found later is a known deviation there, linked to its issue, until it
  is fixed.

## Consequences

**What this makes easy.**

- **A finding has a criterion.** A review names the success criterion a control fails, and the fix is
  done when the control meets it, rather than when somebody is satisfied.
- **Every new control has a bar to meet.** It must be reachable and operable by keyboard, named and
  announced, and meet the contrast and reflow criteria, before it is merged, not in a later scan.

**What this makes hard.**

- **Contrast, reflow and a focus hidden behind the sticky header are checked by hand.** jsdom has no
  layout engine and computes no colour, so the suite cannot observe them. They are checked in a real
  browser, and the pull request says which, until
  [#404](https://github.com/NaimElijah/UpHealther/issues/404) adds an automated check. That check would
  be a new dependency, axe or similar, and needs vetting and its own record first.
- **No screen reader is run.** What a control announces is verified from its role, name, state and live
  region in the DOM. How a given reader voices that is not verified.
- **The phrasing of the standard is the reviewer's to read.** Where a criterion leaves room, such as how
  long is long enough for a message that dismisses itself, the record that settles it says so.

## Alternatives considered

- **WCAG 2.1 AA.** Rejected. 2.2 is the current W3C Recommendation and adds criteria that bear on
  this interface: a focused control not hidden by the sticky header (2.4.11) and the size of a target
  (2.5.8). Content that meets 2.2 AA meets 2.1 AA too, apart from 4.1.1 Parsing, which 2.2
  removed as obsolete, so choosing 2.2 gives up nothing a 2.1 claim needs.
- **No target, keeping FR-40 and NFR-19 alone.** Rejected. The blocker scan 09 found, a page no keyboard
  could reach, was a defect against nothing under that choice, and so would every failure like it be.

## When to revisit

- **A legal or contractual duty to meet a different standard**, such as a public-sector deployment bound
  to EN 301 549 or Section 508. The target then becomes whichever is stricter, criterion by criterion.
- **WCAG 3.0 becomes a W3C Recommendation.** Its conformance model differs from 2.x's, so moving to it is
  a decision of its own, not an update of this one.
