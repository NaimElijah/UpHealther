import React, { useEffect, useRef } from 'react';
import { claimHeadingFocus, PAGE_HEADING_ATTRIBUTE } from './pageHeadingFocus';

/**
 * @param children  the heading's text
 * @param className its styling; the heading carries no look of its own
 */
interface PageHeadingProps {
  children: React.ReactNode;
  className?: string;
}

/**
 * The page's one `h1`, which takes focus after a route change (ADR-083).
 *
 * `tabIndex={-1}` makes it focusable by script and leaves it out of the Tab order. It draws no focus
 * ring: it is not a control, and a ring round a heading would read as something to press. When it
 * mounts after the route change, because its page was loading, it takes the focus the change left
 * waiting (`claimHeadingFocus`).
 */
const PageHeading: React.FC<PageHeadingProps> = ({ children, className = '' }) => {
  const ref = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (ref.current) claimHeadingFocus(ref.current);
  }, []);

  return (
    <h1 ref={ref} tabIndex={-1} {...{ [PAGE_HEADING_ATTRIBUTE]: '' }} className={`focus:outline-none ${className}`}>
      {children}
    </h1>
  );
};

export default PageHeading;
