import { useCallback, useEffect, useRef } from 'react';

/**
 * Asked after each render whether focus can move yet: the element to focus, `null` when there turned
 * out to be nothing to do, or `undefined` for "not yet, ask again after the next render".
 */
export type FocusTarget = () => HTMLElement | null | undefined;

/**
 * Moves focus once an action's result has rendered, for an action that removes or replaces the control
 * that had focus (WCAG 2.4.3). Without it focus falls to `<body>`, and a keyboard user starts again from
 * the top of the page.
 *
 * Returns `focusWhenReady(target)`. The caller asks as its action succeeds, and after every render of
 * the calling component `target` is asked where focus should go, until it answers. Asking again replaces
 * the earlier request. The answer is deferred while a modal dialog is open: the dialog owns focus, and
 * hands it back to its opener as it closes, so the move has to come after that.
 *
 * It checks after every render rather than after a chosen value changes, because what the target waits
 * for differs by caller: a refetched list, a dialog closing, a button that has gone.
 */
export function useFocusWhenReady(): (target: FocusTarget) => void {
  const requested = useRef<FocusTarget | null>(null);

  useEffect(() => {
    const target = requested.current;
    if (!target || document.querySelector('[aria-modal="true"]')) return;
    const element = target();
    if (element === undefined) return;
    requested.current = null;
    element?.focus();
  });

  return useCallback((target: FocusTarget) => {
    requested.current = target;
  }, []);
}
