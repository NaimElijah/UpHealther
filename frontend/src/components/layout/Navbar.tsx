import React from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import Button from '../ui/Button';
import NotificationBell from '../notifications/NotificationBell';
import ThemeToggle from '../theme/ThemeToggle';

/** Top bar: brand link home, the theme toggle, the notification bell, the signed-in user's name, and logout. */
const Navbar: React.FC = () => {
  const { user, logout } = useAuth();

  return (
    <nav className="bg-surface border-b border-line h-16 flex items-center gap-4 px-4 sm:px-6 justify-between sticky top-0 z-30">
      <Link to="/dashboard" className="flex items-center gap-2 shrink-0">
        <span className="text-2xl">💪</span>
        <span className="font-bold text-fg text-lg">UpHealther</span>
      </Link>
      <div className="flex items-center gap-2 sm:gap-3 min-w-0">
        <ThemeToggle />
        <NotificationBell />
        {user && (
          <span className="text-sm text-fg-subtle hidden sm:block min-w-0 truncate">
            Hi, <span className="font-medium">{user.name}</span>
          </span>
        )}
        <Button variant="ghost" size="sm" onClick={logout} className="shrink-0">
          Logout
        </Button>
      </div>
    </nav>
  );
};

export default Navbar;
