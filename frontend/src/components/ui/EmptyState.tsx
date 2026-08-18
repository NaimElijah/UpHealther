import React from 'react';

/**
 * @param icon        emoji shown above the title
 * @param title       what is empty, said plainly
 * @param description optional line explaining how to fill it
 * @param action      optional button, usually the one that creates the first item
 */
interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
}

/**
 * Placeholder for a list with nothing in it.
 *
 * Used instead of rendering nothing, so an empty page reads as "there is nothing here yet" rather than
 * as a page that failed to load.
 */
const EmptyState: React.FC<EmptyStateProps> = ({ icon = '📭', title, description, action }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="text-5xl mb-4">{icon}</div>
    <h3 className="text-lg font-semibold text-gray-800 mb-1">{title}</h3>
    {description && <p className="text-sm text-gray-500 mb-4 max-w-sm">{description}</p>}
    {action && <div>{action}</div>}
  </div>
);

export default EmptyState;
