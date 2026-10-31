import React from 'react';

/**
 * @param size     sm inside a button, md inline, lg for a full-page wait
 * @param announce say "Loading…" to assistive technology; off where the control around the spinner
 *                 already says it is busy, as a loading `Button` does with `aria-busy`
 */
interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  announce?: boolean;
}

const sizeMap = { sm: 'h-4 w-4', md: 'h-8 w-8', lg: 'h-12 w-12' };

/**
 * Indeterminate spinner shown while something is in flight.
 *
 * A status with a visually hidden "Loading…", so a page load or a pending request is not silent to a
 * screen reader (WCAG 1.1.1, 4.1.3). The ring itself is decoration and is hidden. With `announce` off the
 * whole spinner is hidden, so a busy button is not announced twice.
 */
const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ size = 'md', announce = true }) => {
  const ring = (
    <div
      aria-hidden="true"
      className={`${sizeMap[size]} animate-spin rounded-full border-2 border-line-strong border-t-brand`}
    />
  );
  return announce ? (
    <div role="status" className="flex items-center justify-center">
      {ring}
      <span className="sr-only">Loading…</span>
    </div>
  ) : (
    <div aria-hidden="true" className="flex items-center justify-center">
      {ring}
    </div>
  );
};

export default LoadingSpinner;
