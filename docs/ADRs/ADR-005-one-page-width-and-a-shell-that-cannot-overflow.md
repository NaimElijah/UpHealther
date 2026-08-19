# ADR-005: One page width, and a shell that cannot be pushed wider than the window

- **Status:** Accepted
- **Date:** 2026-08-20
- **Scope:** `frontend/` layout, plus one data-only backend migration. No new dependency, no runtime
  behaviour outside the browser.

## Context

The SPA had no layout contract. Each page picked its own width cap as it was written, and by the time
there were nine of them the same window showed the dashboard at 1152px, health areas at 896px and the
daily check-in at 672px, with nothing a reader could reconstruct behind the choice. On a maximised
1536px window `/health-areas` left roughly a third of the content area permanently empty.

Three further things were true and none of them were written down:

- **`<main>` was safe from overflow by accident.** It carried `overflow-auto`, which produces no
  scrollbar (its height is auto, so it never overflows vertically) and looked inert. It was not: CSS
  Flexbox §4.5 gives a flex item an automatic minimum size of zero when its overflow is anything other
  than `visible`. Delete that class and the shell would start being dragged wider than the viewport by
  its own content, with no clue in the diff as to why.
- **Nothing constrained content that came from the database.** The six seeded health areas store
  Material Symbols ligature names in `icon` — `water_drop`, `fitness_center`, `self_improvement` — but
  no icon font is loaded anywhere and the form asks for an emoji. The card rendered the name verbatim
  at `text-2xl`, where it displaced the area's own title and was clipped at the card's edge by `Card`'s
  `overflow-hidden`. Storage, form and render path disagreed about what the field held.
- **A dialog could not fit a short window.** `Modal` centred a panel with no maximum height and no
  scroll region of its own, and guarded only its left and right edges with `mx-4`. A four-field form in
  a 500px-tall viewport overflowed above and below the fold, and the part above it was unreachable
  because nothing scrolled.

None of this can be caught by a test here. jsdom has no layout engine and `vitest.config.ts` sets
`css: false`, so every width, overflow and clipping question is invisible to the suite —
[ADR-004](ADR-004-frontend-test-harness.md) records why a real browser in CI was deferred, and
`requirements.md` §6 has carried that gap as an open question since.

## Decision

**One container decides page width.** `frontend/src/components/ui/PageContainer.tsx` offers two: the
default 1280px (`max-w-7xl`) for lists, grids and dashboards, and 768px (`max-w-3xl`) for reading and
form pages. All nine pages use it. The cap is on the measure — how long a line of text may get — not on
the layout, so below roughly a 1570px viewport a default page simply fills the space the shell gives
it. Horizontal padding stays on `<main>` so a page can never double it.

**The shell is shrinkable by construction, and says so.** `min-w-0` on `<main>` replaces the accidental
`overflow-auto`. A flex item's `min-width` defaults to `auto`, which resolves to its min-content width
and silently overrules `flex-shrink`; `min-w-0` removes that floor. Content that then meets pressure
resolves it itself — `truncate` on the navbar's user name, `break-words` on names and descriptions, a
fixed `h-8 w-8 overflow-hidden` box around a health area's icon, `flex-wrap` on header rows and button
rows.

**A dialog is bounded by its overlay and scrolls its own body.** The overlay carries the gutter as
padding; `max-h-full` resolves against that padded box, so the panel is never taller than the window;
`flex flex-col` with a `shrink-0` header and an `overflow-y-auto` body keeps the title and close button
in place while the form scrolls. It also gained `role="dialog"`, `aria-modal` and an `aria-labelledby`
name derived with `useId`, since three modals share the health areas page.

**Stored icons are reconciled on the render side, then corrected in the data.** `areaIcon.ts` falls back
to the default glyph for anything starting with an ASCII word character, so the layout holds whatever
the database contains; `V5__seed_health_area_icons_as_emoji.sql` then rewrites the six seeded rows as
emoji. The render fix ships first, so at no point does the page depend on the migration having run.

## Consequences

**Easier.** Changing how wide pages are is one edit in one file. A new page inherits the decision
instead of re-making it. A future overflow has one obvious place to look, and the reason `min-w-0` is
there is written next to it rather than implied by a class that appears to do nothing.

**Harder.** A genuinely unshrinkable future child — a wide data table — will now overflow visibly and
give the document a horizontal scrollbar, where `overflow-auto` used to hide it inside `<main>`. That is
the intended trade: a visible bug beats a silent one, and the fix belongs on that child's own wrapper as
an opt-in `overflow-x-auto`.

**Unchanged, and still unverified.** Nothing here is enforced by a test, and this ADR does not add a
gate. Widths, wrapping, the dialog's scroll region and the absence of horizontal overflow were checked
by hand in a browser at the widths listed in the pull request. The one mechanical assertion is
`PageContainer.test.tsx`, which pins which cap each variant selects and explicitly claims nothing about
the resulting layout.

**Two widths changed deliberately.** The daily check-in goes from 672px to 768px and the notification
and progress lists from 768px to 1280px. One reading cap and one default cap beat four arbitrary ones,
and both list pages are rows with right-aligned metadata that read better wide.

**`aria-modal` overstates what the dialog does.** It tells assistive technology the rest of the page is
inert, but nothing traps the keyboard, so Tab still walks out behind the scrim, and focus is neither
moved in on open nor restored on close. Recorded as an open question rather than half-solved.

## Alternatives considered

- **Leave the per-page caps and fix only the modal.** Rejected: the empty band on a wide window was the
  reported symptom, and nine inconsistent caps is the kind of thing that gets copied into the tenth
  page. *Revisit:* never — the inconsistency is the defect.
- **No cap at all; content always fills the window.** Rejected: a 1920px window would give a
  seventy-word line on the upgrade details page. The cap exists for the measure, not for the layout.
- **`overflow-x: hidden` on the shell.** Rejected: it hides the symptom, and it clips any sticky or
  popover child added later — the notification dropdown escapes its card today. *Revisit:* never.
- **Container queries (`@tailwindcss/container-queries`).** Tailwind's breakpoints measure the viewport,
  but the content area is 240px narrower whenever the sidebar is showing, so the column count changes
  later than the numbers suggest — and at exactly `md` the sidebar appears and the content column drops
  from 735px to 480px in one step. Container queries are the correct answer and cost a dependency.
  *Revisit when* a second layout hits the same cliff, or the sidebar becomes collapsible and the
  viewport-to-content offset stops being a constant.
- **A native `<dialog>` with `showModal()`.** The correct answer to the focus trap: a real trap, real
  inertness, native Escape and the top layer, with no hand-rolled key handling. Not adopted here because
  the backdrop moves from a token-styled `<div>` to `::backdrop`, which lives in `index.css` — and
  `check:colours` scans only `index.html` and `src/**/*.ts(x)`, so a colour used there would sit outside
  the one guard that exists to have none. *Revisit when* the dialog needs a genuine keyboard workflow,
  and extend `check:colours` to the stylesheet in the same change.
- **Canonicalising a stored icon to exactly one grapheme with `Intl.Segmenter`.** Rejected: it does not
  type-check under this project's `lib` setting, and its pre-Firefox-125 fallback splits by code point,
  which strips a variation selector from `❤️` and a skin tone from `👍🏽`. The fixed-size clipping box
  already makes the layout immune, so the render path only has to choose between the stored value and
  the default.
- **A mobile navigation drawer.** Out of scope. The sidebar is hidden below `md` deliberately and says
  so; replacing it is a component, focus management and its own requirement.

## Note

Emoji are still written as literals in components — the sidebar's nav icons, the dashboard's stat
glyphs, `EmptyState`. Most are decorative and lack `aria-hidden`, so a screen reader announces them.
`Modal` now marks its own; the rest is a follow-up, not part of this decision.
