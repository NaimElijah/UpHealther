import React from 'react';

/**
 * @param title    the page's heading, rendered as the single h1
 * @param subtitle optional line of context below it
 * @param action   optional button aligned to the right, usually the page's primary action
 */
interface PageHeaderProps {
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
}

/** Standard page heading: title, optional subtitle, optional right-aligned action. */
const PageHeader: React.FC<PageHeaderProps> = ({ title, subtitle, action }) => (
  <div className="flex items-start justify-between mb-6">
    <div>
      <h1 className="text-2xl font-bold text-fg">{title}</h1>
      {subtitle && <p className="mt-1 text-sm text-fg-subtle">{subtitle}</p>}
    </div>
    {action && <div>{action}</div>}
  </div>
);

export default PageHeader;
