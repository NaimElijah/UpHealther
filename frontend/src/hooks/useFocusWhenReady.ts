import { useCallback, useEffect, useRef } from 'react';
import { focusIsFree } from '../lib/focusIsFree';

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
 * Returns `focusWhenReady(target, pressed)`. The caller asks as its action succeeds, and after every
 * render of the calling component `target` is asked where focus should go, until it answers. Asking
 * again replaces the earlier request. The answer is deferred while a modal dialog is open: the dialog
 * owns focus, and hands it back to its opener as it closes, so the move has to come after that.
 *
 * Focus moves only if the user has not moved it meanwhile: when the answer comes, nothing, `<body>` or
 * `pressed`, the control the action was started from, may hold it (`focusIsFree`). Otherwise the request
 * is dropped, and focus stays where the user put it.
 *
 * It checks after every render rather than after a chosen value changes, because what the target waits
 * for differs by caller: a refetched list, a dialog closing, a button that has gone.
 */
export function useFocusWhenReady(): (target: FocusTarget, pressed?: Element | null) => void {
  const requested = useRef<{ target: FocusTarget; pressed: Element | null } | null>(null);

  useEffect(() => {
    const request = requested.current;
    if (!request || document.querySelector('[aria-modal="true"]')) return;
    const element = request.target();
    if (element === undefined) return;
    requested.current = null;
    if (focusIsFree(request.pressed)) element?.focus();
  });

  return useCallback((target: FocusTarget, pressed: Element | null = null) => {
    requested.current = { target, pressed };
  }, []);
}
