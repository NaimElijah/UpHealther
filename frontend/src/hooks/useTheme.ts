import { useContext } from 'react';
import { ThemeContext } from '../contexts/themeContextValue';

/**
 * Reads the theme context: the chosen theme, what it resolves to now, and the setter.
 *
 * @throws Error when called outside `ThemeProvider` — a null context would otherwise surface much later
 * as an unexplained "cannot read property of null" inside a component.
 */
export const useTheme = () => {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
};
