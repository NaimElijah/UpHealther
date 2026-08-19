import React from 'react';
import { NavLink } from 'react-router-dom';

/** One sidebar entry: where it goes, what it is called, and the emoji standing in for an icon. */
interface NavItem {
  to: string;
  label: string;
  icon: string;
}

/**
 * The primary navigation, in the order the app is meant to be used: plan, then run, then review.
 *
 * This list is the sidebar's whole content — adding a page here is what puts it in the navigation, and
 * the route itself still has to be registered in the router.
 */
const navItems: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: '🏠' },
  { to: '/health-areas', label: 'Health Areas', icon: '🎯' },
  { to: '/upgrades/backlog', label: 'Idea Backlog', icon: '💡' },
  { to: '/upgrades/planned', label: 'Planned', icon: '📅' },
  { to: '/upgrades/active', label: 'Active', icon: '🔥' },
  { to: '/daily-checkin', label: 'Daily Check-in', icon: '✅' },
  { to: '/progress-history', label: 'Progress History', icon: '📈' },
  { to: '/notifications', label: 'Notifications', icon: '🔔' },
];

/**
 * Primary navigation rail, with the current route highlighted.
 *
 * Hidden below the medium breakpoint — on a phone the navbar is the only chrome, and the pages are
 * reachable from the dashboard.
 */
const Sidebar: React.FC = () => (
  <aside className="w-60 bg-surface border-r border-line min-h-full flex-shrink-0 hidden md:block">
    <nav className="py-4">
      {navItems.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          className={({ isActive }) =>
            `flex items-center gap-3 px-4 py-2.5 text-sm font-medium transition-colors ${
              isActive
                ? 'bg-brand-soft text-brand-fg border-r-2 border-brand'
                : 'text-fg-subtle hover:bg-muted hover:text-fg'
            }`
          }
        >
          <span>{item.icon}</span>
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  </aside>
);

export default Sidebar;
