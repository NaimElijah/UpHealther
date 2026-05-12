import React from 'react';

interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg';
}

const sizeMap = { sm: 'h-4 w-4', md: 'h-8 w-8', lg: 'h-12 w-12' };

const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ size = 'md' }) => (
  <div className="flex items-center justify-center">
    <div
      className={`${sizeMap[size]} animate-spin rounded-full border-2 border-gray-300 border-t-blue-600`}
    />
  </div>
);

export default LoadingSpinner;
