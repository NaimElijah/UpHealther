import { createContext } from 'react';

/**
 * The three states the user can choose between.
 *
 * `system` is not a snapshot of the operating system's preference taken at boot — it is a standing
 * instruction to follow it, including while the tab is open.
 */
export type Theme = 'light' | 'dark' | 'system';

/**
 * What the theme context exposes: the user's choice, what that choice currently resolves to, and the
 * setter that persists it.
 *
 * `theme` and `resolvedTheme` are both needed and are not interchangeable. Anything rendering the
 * *choice* must read `theme`, or selecting "System" would light up "Dark"; anything rendering the
 * *effect* must read `resolvedTheme`.
 */
export interface ThemeContextType {
  theme: Theme;
  resolvedTheme: 'light' | 'dark';
  setTheme: (theme: Theme) => void;
}

/**
 * The context object itself, kept in its own module so the file holding the provider component exports
 * only components — which is what lets React Fast Refresh work on it.
 */
export const ThemeContext = createContext<ThemeContextType | null>(null);
