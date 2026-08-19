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

const variantClasses: Record<NonNullable<BadgeProps['variant']>, string> = {
  green: 'bg-tint-green-soft text-tint-green-fg',
  yellow: 'bg-tint-yellow-soft text-tint-yellow-fg',
  blue: 'bg-tint-blue-soft text-tint-blue-fg',
  red: 'bg-tint-red-soft text-tint-red-fg',
  gray: 'bg-muted text-fg-muted',
  purple: 'bg-tint-purple-soft text-tint-purple-fg',
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
