import React from 'react';
import NavLinks from './NavLinks';

/**
 * Primary navigation rail, with the current route highlighted.
 *
 * Hidden below the medium breakpoint — on a phone the navbar is the only chrome, and the pages are
 * reachable from the dashboard.
 */
const Sidebar: React.FC = () => (
  <aside className="w-60 bg-surface border-r border-line min-h-full flex-shrink-0 hidden md:block">
    <NavLinks />
  </aside>
);

export default Sidebar;
