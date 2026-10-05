import React from 'react';
import NavLinks from './NavLinks';

/**
 * Primary navigation rail, with the current route highlighted.
 *
 * Hidden below the medium breakpoint, where the navbar's `NavMenu` offers the same links in a dialog
 * instead. The two share `NavLinks`, so neither can offer a page the other does not.
 */
const Sidebar: React.FC = () => (
  <aside className="w-60 bg-surface border-r border-line min-h-full flex-shrink-0 hidden md:block">
    <NavLinks />
  </aside>
);

export default Sidebar;
