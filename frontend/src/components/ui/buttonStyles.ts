/**
 * How a button looks, shared by `Button` and `ButtonLink`, so a link that is styled as a button cannot
 * drift from the buttons beside it. A module of its own because `react-refresh/only-export-components`
 * refuses a component file that exports a function.
 */

/**
 * Visual weight: primary for the main action, danger for destructive ones; secondary is a muted fill and ghost
 * has none until hovered.
 */
export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

/** Padding and text scale. */
export type ButtonSize = 'sm' | 'md' | 'lg';

const variantClasses: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-fg-inverted hover:bg-brand-hover',
  secondary: 'bg-muted text-fg-muted hover:bg-muted-strong',
  danger: 'bg-danger text-fg-inverted hover:bg-danger-hover',
  ghost: 'bg-transparent text-fg-subtle hover:bg-muted',
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: 'px-3 py-1.5 text-sm',
  md: 'px-4 py-2 text-sm',
  lg: 'px-6 py-3 text-base',
};

/**
 * The classes for a button of this variant and size, with the caller's own appended so they win.
 *
 * @param variant   its visual weight
 * @param size      its padding and text scale
 * @param className extra classes from the caller
 */
export const buttonClasses = (variant: ButtonVariant, size: ButtonSize, className = ''): string =>
  `inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed aria-disabled:opacity-50 aria-disabled:cursor-not-allowed ${variantClasses[variant]} ${sizeClasses[size]} ${className}`;
