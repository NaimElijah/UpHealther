import React from 'react';
import LoadingSpinner from './LoadingSpinner';
import { buttonClasses, type ButtonSize, type ButtonVariant } from './buttonStyles';

/**
 * Extends the native button attributes, so `type`, `onClick`, `aria-*` and the rest pass straight
 * through.
 *
 * @param variant visual weight: primary for the main action, danger for destructive ones
 * @param size    padding and text scale
 * @param loading shows a spinner and refuses presses, so a slow request cannot be double-submitted; the
 *                button stays focusable meanwhile, and `disabled` is ignored while it is set
 */
interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  children: React.ReactNode;
}

/**
 * The app's button. While `loading` it refuses every press, which is what prevents duplicate
 * submissions.
 *
 * It refuses them with `aria-disabled` and a click that does nothing, not with `disabled`, because a
 * disabled button gives up focus: pressing Save dropped focus to `<body>` for as long as the save took
 * (WCAG 2.4.3). Cancelling the click's default is what stops a submit button sending its form again,
 * and it covers Enter in one of the form's fields too, which a browser turns into a click on the submit
 * button. `aria-busy` says why the button is unavailable, so its spinner announces nothing of its own.
 * A caller's own `disabled` is ignored while loading for the same reason as `disabled` is not used:
 * several pages also disable the button while their write is in flight.
 *
 * Forwards its ref to the `<button>`, so a caller can move focus to it when the control that had focus
 * is gone.
 */
const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    { variant = 'primary', size = 'md', loading = false, children, disabled, onClick, className = '', ...rest },
    ref,
  ) => (
    <button
      {...rest}
      ref={ref}
      disabled={disabled && !loading}
      aria-disabled={loading || undefined}
      aria-busy={loading || undefined}
      onClick={(e) => {
        if (loading) {
          e.preventDefault();
          return;
        }
        onClick?.(e);
      }}
      className={buttonClasses(variant, size, className)}
    >
      {loading && <LoadingSpinner size="sm" announce={false} />}
      {children}
    </button>
  ),
);
Button.displayName = 'Button';

export default Button;
