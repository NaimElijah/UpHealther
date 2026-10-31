import React, { useEffect, useRef, useState } from 'react';
import { claimHeadingFocus, PAGE_HEADING_ATTRIBUTE } from './pageHeadingFocus';

/**
 * @param children  the heading's text
 * @param className its styling; the heading carries no look of its own
 * @param takeFocus focus it as it mounts, for a view that replaces the control the user had just used,
 *                  such as the check-in's completion replacing its form
 */
interface PageHeadingProps {
  children: React.ReactNode;
  className?: string;
  takeFocus?: boolean;
}

/**
 * The page's one `h1`, which takes focus after a route change (ADR-083).
 *
 * `tabIndex={-1}` makes it focusable by script and leaves it out of the Tab order. It draws no focus
 * ring: it is not a control, and a ring round a heading would read as something to press. When it
 * mounts after the route change, because its page was loading, it takes the focus the change left
 * waiting (`claimHeadingFocus`).
 */
const PageHeading: React.FC<PageHeadingProps> = ({ children, className = '', takeFocus = false }) => {
  const ref = useRef<HTMLHeadingElement>(null);
  // Read once: `takeFocus` says how the heading arrived, and changing it later is not an arrival.
  const [focusOnArrival] = useState(takeFocus);

  useEffect(() => {
    if (!ref.current) return;
    if (focusOnArrival) ref.current.focus();
    else claimHeadingFocus(ref.current);
  }, [focusOnArrival]);

  return (
    <h1 ref={ref} tabIndex={-1} {...{ [PAGE_HEADING_ATTRIBUTE]: '' }} className={`focus:outline-none ${className}`}>
      {children}
    </h1>
  );
};

export default PageHeading;
