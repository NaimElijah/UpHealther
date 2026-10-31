import React from 'react';

/**
 * @param children     the card body
 * @param header       optional title, drawn as a heading row separated by a rule; omitted entirely when
 *                     not given
 * @param headerAction optional control drawn beside the title, outside the heading, such as "+ Log
 *                     Progress"
 * @param level        the title's heading level: 2 for a section of the page, the default, and 3 for a card
 *                     inside a section that already has one
 * @param className    extra classes for the card itself
 */
interface CardProps {
  children: React.ReactNode;
  header?: React.ReactNode;
  headerAction?: React.ReactNode;
  level?: 2 | 3;
  className?: string;
}

/**
 * Themed panel with an optional header. The standard container for a section of a page.
 *
 * The header is a heading, so the page's sections are in its outline and a screen reader can move
 * between them (WCAG 1.3.1, 2.4.6). It carries no classes of its own: Preflight resets a heading's size
 * and weight to inherit, so the row's styling is what it looks like, as before. An action goes beside the
 * heading, never inside it, where it would become part of the section's name.
 */
const Card: React.FC<CardProps> = ({ children, header, headerAction, level = 2, className = '' }) => {
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <div className={`bg-surface rounded-xl shadow-sm border border-line overflow-hidden ${className}`}>
      {header && (
        <div
          className={`px-6 py-4 border-b border-line font-semibold text-fg-muted ${
            headerAction ? 'flex items-center justify-between' : ''
          }`}
        >
          <Heading>{header}</Heading>
          {headerAction}
        </div>
      )}
      <div className="p-6">{children}</div>
    </div>
  );
};

export default Card;
