import React from 'react';

/**
 * @param children  the page's content
 * @param width     how far the content may grow — `wide` for lists, grids and dashboards, `narrow` for
 *                  reading and form pages, where a long line of text is harder to follow
 * @param className extra classes for the container itself, usually vertical rhythm
 */
interface PageContainerProps {
  children: React.ReactNode;
  width?: 'wide' | 'narrow';
  className?: string;
}

/** The two caps, by name. Not exported: `react-refresh/only-export-components` plus CI's zero-warning lint. */
const widthClasses: Record<NonNullable<PageContainerProps['width']>, string> = {
  wide: 'max-w-7xl',
  narrow: 'max-w-3xl',
};

/**
 * The one place a page's width is decided.
 *
 * Pages used to cap themselves, with four different values across nine pages and nothing behind the
 * choice — which is how `/health-areas` came to sit 896px wide in the middle of a 1536px window. A page
 * now fills whatever the shell gives it and stops only where a line of text stops being comfortable to
 * read.
 *
 * So the cap is on the measure, not on the layout. At the 16px body size a proportional character
 * averages roughly 8px, which puts `wide` (1280px) near 160 characters and `narrow` (768px) near 95 —
 * the latter about the upper bound of the usual 45–90 guidance, and why prose and form pages take it.
 * Given the 240px sidebar and the shell's padding, `wide` does not begin to bite until about a 1570px
 * viewport; below that a page simply fills the space.
 */
const PageContainer: React.FC<PageContainerProps> = ({ children, width = 'wide', className = '' }) => (
  <div className={`w-full mx-auto ${widthClasses[width]} ${className}`}>{children}</div>
);

export default PageContainer;
