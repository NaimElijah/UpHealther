import React from 'react';
import { NavLink } from 'react-router-dom';

interface NavItem {
  to: string;
  label: string;
  icon: string;
}

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

const Sidebar: React.FC = () => (
  <aside className="w-60 bg-gray-50 border-r border-gray-200 min-h-full flex-shrink-0 hidden md:block">
    <nav className="py-4">
      {navItems.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          className={({ isActive }) =>
            `flex items-center gap-3 px-4 py-2.5 text-sm font-medium transition-colors ${
              isActive
                ? 'bg-blue-50 text-blue-700 border-r-2 border-blue-600'
                : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
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
