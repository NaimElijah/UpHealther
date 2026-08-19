import React from 'react';

/**
 * @param children the card body
 * @param header   optional heading row, separated by a rule; omitted entirely when not given
 */
interface CardProps {
  children: React.ReactNode;
  header?: React.ReactNode;
  className?: string;
}

/** Themed panel with an optional header. The standard container for a section of a page. */
const Card: React.FC<CardProps> = ({ children, header, className = '' }) => (
  <div className={`bg-surface rounded-xl shadow-sm border border-line overflow-hidden ${className}`}>
    {header && (
      <div className="px-6 py-4 border-b border-line font-semibold text-fg-muted">
        {header}
      </div>
    )}
    <div className="p-6">{children}</div>
  </div>
);

export default Card;
