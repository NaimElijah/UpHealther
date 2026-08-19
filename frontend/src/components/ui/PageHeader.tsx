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

/**
 * Standard page heading: title, optional subtitle, optional right-aligned action.
 *
 * Wraps rather than squeezes. Below the width at which the heading and the action fit on one line the
 * action drops underneath, which keeps a long title readable instead of shrinking the button beside
 * it. `min-w-0` is what lets the heading block wrap at all — without it the block's min-content width
 * is its floor, and the row grows past its container instead.
 */
const PageHeader: React.FC<PageHeaderProps> = ({ title, subtitle, action }) => (
  <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
    <div className="min-w-0">
      <h1 className="text-2xl font-bold text-fg">{title}</h1>
      {subtitle && <p className="mt-1 text-sm text-fg-subtle">{subtitle}</p>}
    </div>
    {action && <div className="shrink-0">{action}</div>}
  </div>
);

export default PageHeader;
