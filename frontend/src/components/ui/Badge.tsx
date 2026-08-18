import React from 'react';

/**
 * @param variant colour scheme; the palette is fixed so badges stay consistent across the app
 * @param children the label
 * @param className extra classes appended after the variant's, so they win on conflict
 */
interface BadgeProps {
  variant?: 'green' | 'yellow' | 'blue' | 'red' | 'gray' | 'purple';
  children: React.ReactNode;
  className?: string;
}

const variantClasses: Record<string, string> = {
  green: 'bg-green-100 text-green-800',
  yellow: 'bg-yellow-100 text-yellow-800',
  blue: 'bg-blue-100 text-blue-800',
  red: 'bg-red-100 text-red-800',
  gray: 'bg-gray-100 text-gray-700',
  purple: 'bg-purple-100 text-purple-800',
};

/** Small rounded label used for statuses, types and difficulties. */
const Badge: React.FC<BadgeProps> = ({ variant = 'gray', children, className = '' }) => (
  <span
    className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${variantClasses[variant]} ${className}`}
  >
    {children}
  </span>
);

export default Badge;
