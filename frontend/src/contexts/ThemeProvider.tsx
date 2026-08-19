import React, { useCallback, useEffect, useState } from 'react';
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
  const [systemPrefersDark, setSystemPrefersDark] = useState(() => darkMediaQuery()?.matches ?? false);

  // `system` means "follow it", not "follow it once at boot", so the query is subscribed to rather than
  // sampled. Under StrictMode this effect runs, tears down and runs again on mount; the removal in the
  // cleanup is the only thing stopping that from leaving two listeners behind, which would flip the
  // resolved theme twice per change.
  useEffect(() => {
    const query = darkMediaQuery();
    if (!query) return;

    // Re-read here: the preference can change between the first render and this effect, and the initial
    // state would then be stale with no event coming to correct it.
    setSystemPrefersDark(query.matches);

    const handleChange = (event: MediaQueryListEvent) => setSystemPrefersDark(event.matches);
    query.addEventListener('change', handleChange);
    return () => query.removeEventListener('change', handleChange);
  }, []);

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
