import React from 'react';
import LoadingSpinner from './LoadingSpinner';

/**
 * Extends the native button attributes, so `type`, `onClick`, `aria-*` and the rest pass straight
 * through.
 *
 * @param variant visual weight: primary for the main action, danger for destructive ones
 * @param size    padding and text scale
 * @param loading shows a spinner and disables the button, so a slow request cannot be double-submitted
 */
interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  children: React.ReactNode;
}

const variantClasses: Record<string, string> = {
  primary: 'bg-brand text-fg-inverted hover:bg-brand-hover',
  secondary: 'bg-muted text-fg-muted hover:bg-muted-strong',
  danger: 'bg-danger text-fg-inverted hover:bg-danger-hover',
  ghost: 'bg-transparent text-fg-subtle hover:bg-muted',
};

const sizeClasses: Record<string, string> = {
  sm: 'px-3 py-1.5 text-sm',
  md: 'px-4 py-2 text-sm',
  lg: 'px-6 py-3 text-base',
};

/** The app's button. Disabled while `loading`, which is what prevents duplicate submissions. */
const Button: React.FC<ButtonProps> = ({
  variant = 'primary',
  size = 'md',
  loading = false,
  children,
  disabled,
  className = '',
  ...rest
}) => (
  <button
    disabled={disabled || loading}
    className={`inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed ${variantClasses[variant]} ${sizeClasses[size]} ${className}`}
    {...rest}
  >
    {loading && <LoadingSpinner size="sm" />}
    {children}
  </button>
);

export default Button;
