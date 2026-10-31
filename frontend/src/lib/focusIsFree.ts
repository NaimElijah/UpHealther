/**
 * Whether focus can be moved without taking it from where the user put it (WCAG 2.4.3): nothing has it,
 * `<body>` has it, which is where a browser drops it when the focused control is removed, or the control
 * the action was started from still has it.
 *
 * A hand-on that does not ask first throws a user who moved on while a request was in flight back to
 * wherever the page decided, often its top.
 *
 * @param pressed the control the action was started from, if there was one. It counts as "not moved"
 *                because a button that disables itself keeps focus in Chromium but drops it to `<body>`
 *                in Firefox, and the hand-on has to work in both
 * @returns true when moving focus now takes nothing from the user
 */
export function focusIsFree(pressed: Element | null = null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || (pressed !== null && active === pressed);
}
