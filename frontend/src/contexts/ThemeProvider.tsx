import React, { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { ThemeContext } from './themeContextValue';
import type { Theme } from './themeContextValue';

/** Shared with the boot script in `index.html`; changing one without the other reintroduces the flash. */
const STORAGE_KEY = 'theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * Reads the saved choice, treating anything unrecognised as `system`.
 *
 * Guarded because reading `localStorage` can throw outright rather than return null — Firefox with
 * storage disabled raises a SecurityError on property access, as does a third-party context with site
 * data blocked. There is no error boundary above this provider, so an unguarded read would blank the
 * whole application rather than degrade.
 */
const readStoredTheme = (): Theme => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    // Storage unavailable: fall back to following the operating system.
    return 'system';
  }
};

/** The dark-preference query, or null where `matchMedia` is missing (some embedded webviews). */
const darkMediaQuery = (): MediaQueryList | null =>
  typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null;

/**
 * Subscribes to changes of the operating system's colour scheme, for `useSyncExternalStore`.
 *
 * @param onChange called on every change
 * @returns the unsubscribe; without `matchMedia` there is nothing to subscribe to, and it does nothing
 */
const subscribeToDarkPreference = (onChange: () => void): (() => void) => {
  const query = darkMediaQuery();
  if (!query) return () => undefined;
  query.addEventListener('change', onChange);
  return () => query.removeEventListener('change', onChange);
};

/** Whether the operating system currently prefers dark; false where `matchMedia` is missing. */
const systemPrefersDarkNow = (): boolean => darkMediaQuery()?.matches ?? false;

/**
 * Owns the theme: the user's choice, the operating system's preference, and the `dark` class on
 * `<html>` that the whole stylesheet keys off.
 *
 * Mounted outermost, above the query client, because the theme is neither server state nor tied to a
 * session — the login page needs it as much as the dashboard does.
 *
 * The class is also set before React runs, by the boot script in `index.html`. That script is what
 * prevents the flash; this provider is what keeps the class true afterwards. The two agree by
 * construction: same key, same values, same resolution rule.
 */
export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setThemeState] = useState<Theme>(readStoredTheme);

  // `system` means "follow it", not "follow it once at boot", so the query is subscribed to rather than
  // sampled. `useSyncExternalStore` reads the preference again once it has subscribed, so a change
  // between the first render and the subscription is not missed, and it unsubscribes on unmount, so
  // StrictMode's second mount leaves one listener rather than two.
  const systemPrefersDark = useSyncExternalStore(subscribeToDarkPreference, systemPrefersDarkNow);

  const resolvedTheme = theme === 'system' ? (systemPrefersDark ? 'dark' : 'light') : theme;

  // The two-argument `toggle` sets the class to a value rather than flipping it, so a double invocation
  // under StrictMode lands in the same place as a single one.
  useEffect(() => {
    document.documentElement.classList.toggle('dark', resolvedTheme === 'dark');
  }, [resolvedTheme]);

  /** Records the choice. The state change is deliberately not conditional on the write succeeding. */
  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Storage full or blocked. The theme still changes for this session; it just will not survive a
      // reload, which is a better outcome than refusing to switch at all.
    }
  }, []);

  return (
    <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>{children}</ThemeContext.Provider>
  );
};
