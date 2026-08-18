import React from 'react';
import Navbar from './Navbar';
import Sidebar from './Sidebar';

/** @param children the page to render in the content area */
interface LayoutProps {
  children: React.ReactNode;
}

/**
 * The frame every signed-in page renders inside: fixed navbar, sidebar, and a scrolling content area.
 *
 * Applied by the router rather than by each page, so no authenticated page can be added without it.
 */
const Layout: React.FC<LayoutProps> = ({ children }) => (
  <div className="min-h-screen bg-gray-50 flex flex-col">
    <Navbar />
    <div className="flex flex-1">
      <Sidebar />
      <main className="flex-1 p-6 overflow-auto">{children}</main>
    </div>
  </div>
);

export default Layout;
