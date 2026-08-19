/**
 * A `window.matchMedia` that tests can drive.
 *
 * jsdom implements `matchMedia`, but nothing can change what it reports — so the branch that matters
 * most, the operating system's colour scheme flipping while the tab is open, would be unreachable.
 * This stub exposes `setMatches`, which updates `matches` and notifies every registered listener, and
 * `listenerCount`, which is how a test asserts that a subscription was cleaned up rather than leaked.
 */
export interface MatchMediaStub {
  /** Changes what the query reports and fires `change` at every registered listener. */
  setMatches: (matches: boolean) => void;
  /** How many listeners are currently registered. Zero after a correct unmount. */
  listenerCount: () => number;
}

/**
 * Replaces `window.matchMedia` with a controllable stub.
 *
 * Installed for every test by `setupTests.ts` reporting `false`, so a test that does not care about the
 * system preference still gets a deterministic one. Call it again with `true` to start dark.
 */
export const installMatchMedia = (initialMatches = false): MatchMediaStub => {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  let matches = initialMatches;

  const query = {
    get matches() {
      return matches;
    },
    media: '(prefers-color-scheme: dark)',
    onchange: null,
    addEventListener: (_type: string, listener: EventListener) =>
      listeners.add(listener as (event: MediaQueryListEvent) => void),
    removeEventListener: (_type: string, listener: EventListener) =>
      listeners.delete(listener as (event: MediaQueryListEvent) => void),
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as unknown as MediaQueryList;

  window.matchMedia = () => query;

  return {
    setMatches: (next: boolean) => {
      matches = next;
      listeners.forEach((listener) => listener({ matches: next } as MediaQueryListEvent));
    },
    listenerCount: () => listeners.size,
  };
};

/**
 * Removes `window.matchMedia` entirely, standing in for the embedded webviews that lack it.
 *
 * `Reflect.deleteProperty` rather than `delete window.matchMedia`, which TypeScript rejects on a
 * non-optional property.
 */
export const uninstallMatchMedia = (): void => {
  Reflect.deleteProperty(window, 'matchMedia');
};
