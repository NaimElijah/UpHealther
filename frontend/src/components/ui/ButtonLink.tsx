import React from 'react';
import { Link } from 'react-router-dom';
import { buttonClasses, type ButtonSize, type ButtonVariant } from './buttonStyles';

/**
 * @param to        the route it opens
 * @param variant   visual weight, as `Button`'s
 * @param size      padding and text scale, as `Button`'s
 * @param className extra classes, appended after the button's own
 * @param children  the link's text, which is its name
 */
interface ButtonLinkProps {
  to: string;
  variant?: ButtonVariant;
  size?: ButtonSize;
  className?: string;
  children: React.ReactNode;
}

/**
 * A link that looks like `Button`, for what only takes the user to another page (WCAG 4.1.2).
 *
 * A button that calls `navigate` is announced as a button, so a screen reader user expects it to act on
 * the page, and it cannot be opened in a new tab or have its address copied. A link does both, and the
 * shared classes keep it looking like the buttons beside it.
 */
const ButtonLink: React.FC<ButtonLinkProps> = ({ to, variant = 'primary', size = 'md', className = '', children }) => (
  <Link to={to} className={buttonClasses(variant, size, className)}>
    {children}
  </Link>
);

export default ButtonLink;
