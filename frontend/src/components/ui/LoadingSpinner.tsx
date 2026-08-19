import React from 'react';

/** @param size sm inside a button, md inline, lg for a full-page wait */
interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg';
}

const sizeMap = { sm: 'h-4 w-4', md: 'h-8 w-8', lg: 'h-12 w-12' };

/** Indeterminate spinner shown while something is in flight. */
const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ size = 'md' }) => (
  <div className="flex items-center justify-center">
    <div
      className={`${sizeMap[size]} animate-spin rounded-full border-2 border-line-strong border-t-brand`}
    />
  </div>
);

export default LoadingSpinner;
