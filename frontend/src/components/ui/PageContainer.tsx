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

/**
 * The one place a page's width is decided.
 *
 * Pages used to cap themselves, with four different values across nine pages and nothing behind the
 * choice — which is how `/health-areas` came to sit 896px wide in the middle of a 1536px window. A page
 * now fills whatever the shell gives it and stops only where a line of text stops being comfortable to
 * read.
 *
 * So the cap is on the measure, not on the layout: `wide` is roughly a hundred characters at the body
 * size, `narrow` roughly sixty. Given the 240px sidebar and the shell's padding, `wide` does not begin
 * to bite until about a 1570px viewport — below that a page simply fills the space.
 */
const widthClasses: Record<NonNullable<PageContainerProps['width']>, string> = {
  wide: 'max-w-7xl',
  narrow: 'max-w-3xl',
};

const PageContainer: React.FC<PageContainerProps> = ({ children, width = 'wide', className = '' }) => (
  <div className={`w-full mx-auto ${widthClasses[width]} ${className}`}>{children}</div>
);

export default PageContainer;
