import React from 'react';

/**
 * @param icon        emoji shown above the title; 📭 when not given
 * @param title       what is empty, said plainly
 * @param description optional line explaining how to fill it
 * @param action      optional button, usually the one that creates the first item
 * @param level       the title's heading level: 2 where the empty list is the page's content, the default,
 *                    and 3 inside a section that has a heading of its own
 */
interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
  level?: 2 | 3;
}

/**
 * Placeholder for a list with nothing in it.
 *
 * Used instead of rendering nothing, so an empty page reads as "there is nothing here yet" rather than
 * as a page that failed to load.
 */
const EmptyState: React.FC<EmptyStateProps> = ({ icon = '📭', title, description, action, level = 2 }) => {
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="text-5xl mb-4" aria-hidden="true">
        {icon}
      </div>
      <Heading className="text-lg font-semibold text-fg-muted mb-1">{title}</Heading>
      {description && <p className="text-sm text-fg-subtle mb-4 max-w-sm">{description}</p>}
      {action && <div>{action}</div>}
    </div>
  );
};

export default EmptyState;
