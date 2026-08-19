import React from 'react';
import Navbar from './Navbar';
import Sidebar from './Sidebar';

/** @param children the page to render in the content area */
interface LayoutProps {
  children: React.ReactNode;
}

/**
 * The frame every signed-in page renders inside: a sticky navbar, the sidebar rail, and the content
 * area. Applied by the router rather than by each page, so no authenticated page can be added without
 * it. How wide the page inside it may grow is decided by `PageContainer`, not here.
 *
 * `min-w-0` on the content column is load-bearing. A flex item's `min-width` defaults to `auto`, which
 * resolves to its *min-content* width, so one child that cannot shrink — a long unbroken string, a wide
 * table — overrules `flex-shrink` and drags the column past the viewport. `min-w-0` replaces that floor
 * with zero and gives the shrinking back; the child then has to resolve the pressure itself, which is
 * what the `truncate` and `break-words` rules further down the tree are for.
 *
 * It replaces an `overflow-auto` that was doing the same job by accident: a flex item whose overflow is
 * not `visible` already has an automatic minimum size of zero. That was worth stating outright rather
 * than leaving as a side effect — and it made `<main>` a scroll container, which would clip any sticky
 * or overflowing child added inside it later. The cost is that a genuinely unshrinkable future child
 * now overflows visibly instead of scrolling inside `<main>`; that is the better failure, and the fix
 * then belongs on that child's own wrapper.
 */
const Layout: React.FC<LayoutProps> = ({ children }) => (
  <div className="min-h-screen bg-canvas flex flex-col">
    <Navbar />
    <div className="flex flex-1">
      <Sidebar />
      <main className="flex-1 min-w-0 p-4 sm:p-6">{children}</main>
    </div>
  </div>
);

export default Layout;
