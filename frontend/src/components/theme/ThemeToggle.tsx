import React from 'react';
import { useTheme } from '../../hooks/useTheme';
import type { Theme } from '../../contexts/themeContextValue';

/**
 * Shared icon attributes.
 *
 * `stroke="currentColor"` is the point: an SVG icon inherits the token colour and therefore follows the
 * theme it switches, where an emoji glyph carries its own fixed colours and cannot.
 */
const iconProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  className: 'w-4 h-4',
  'aria-hidden': true,
};

const SunIcon: React.FC = () => (
  <svg {...iconProps}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
);

const MonitorIcon: React.FC = () => (
  <svg {...iconProps}>
    <rect x="2" y="4" width="20" height="13" rx="2" />
    <path d="M8 21h8M12 17v4" />
  </svg>
);

const MoonIcon: React.FC = () => (
  <svg {...iconProps}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
  </svg>
);

/** The three states in increasing darkness, so the control reads as a scale rather than a menu. */
const options: { value: Theme; label: string; icon: React.ReactNode }[] = [
  { value: 'light', label: 'Light', icon: <SunIcon /> },
  { value: 'system', label: 'System', icon: <MonitorIcon /> },
  { value: 'dark', label: 'Dark', icon: <MoonIcon /> },
];

/**
 * Three-state theme control: light, system, dark.
 *
 * Native radio inputs rather than buttons with ARIA. A radio group is what this is, and the real
 * element brings arrow-key navigation, the group relationship and the "2 of 3, selected" announcement
 * with it — none of which comes free from three buttons. The inputs are `sr-only`, which clips them
 * visually while leaving them focusable and operable.
 *
 * The System option names what it currently resolves to, because "System" alone gives a screen-reader
 * user the rule but not the outcome — and the outcome is otherwise conveyed by colour alone.
 */
const ThemeToggle: React.FC = () => {
  const { theme, resolvedTheme, setTheme } = useTheme();

  return (
    <fieldset className="flex items-center gap-0.5 rounded-full bg-muted p-0.5">
      <legend className="sr-only">Theme</legend>
      {options.map((option) => (
        <label key={option.value} title={option.label} className="cursor-pointer">
          <input
            type="radio"
            name="theme"
            value={option.value}
            checked={theme === option.value}
            onChange={() => setTheme(option.value)}
            className="peer sr-only"
          />
          <span className="flex w-8 h-8 items-center justify-center rounded-full text-fg-subtle transition-colors peer-hover:text-fg peer-checked:bg-brand peer-checked:text-fg-inverted peer-focus-visible:ring-2 peer-focus-visible:ring-focus peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-muted">
            {option.icon}
            <span className="sr-only">
              {option.value === 'system' ? `${option.label} (currently ${resolvedTheme})` : option.label}
            </span>
          </span>
        </label>
      ))}
    </fieldset>
  );
};

export default ThemeToggle;
