/**
 * Moving focus to the page's heading after a route change (ADR-083), shared by the router, which knows
 * when the route changed, and `PageHeading`, which knows when the heading exists.
 *
 * A module of its own rather than exports of `PageHeading.tsx`, because
 * `react-refresh/only-export-components` refuses a component file that exports functions.
 */

/** Marks the one element per page that takes focus after a route change. */
export const PAGE_HEADING_ATTRIBUTE = 'data-page-heading';

/**
 * A move the router asked for while the new page had no heading yet, because it was still loading. It
 * remembers what had focus at that moment, so a heading that arrives later takes focus only if the user
 * has not moved it in the meantime. `null` while nothing is waiting.
 */
let waiting: { focusedAtNavigation: Element | null } | null = null;

/**
 * Focuses the current page's heading, or, when the page has not rendered one yet, leaves the move for
 * the heading to make once it mounts (`claimHeadingFocus`).
 *
 * Called by the router after a location change and never on the first render: on a fresh load the
 * browser's own starting point, the top of the document, is already right.
 */
export function moveFocusToPageHeading(): void {
  const heading = document.querySelector<HTMLElement>(`[${PAGE_HEADING_ATTRIBUTE}]`);
  if (heading) {
    waiting = null;
    heading.focus();
    return;
  }
  waiting = { focusedAtNavigation: document.activeElement };
}

/**
 * Takes the focus a route change left waiting, if there is one and nothing else has taken it since.
 * Focus that the user moved while the page loaded is theirs, and stealing it back once the data arrived
 * would throw them to the top of the page for no reason they could see.
 *
 * @param heading the page heading that has just mounted
 */
export function claimHeadingFocus(heading: HTMLElement): void {
  if (!waiting) return;
  const { focusedAtNavigation } = waiting;
  waiting = null;
  const active = document.activeElement;
  if (active === null || active === document.body || active === focusedAtNavigation) heading.focus();
}
