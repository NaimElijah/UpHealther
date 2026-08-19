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
    <nav className="bg-surface border-b border-line h-16 flex items-center px-6 justify-between sticky top-0 z-30">
      <Link to="/dashboard" className="flex items-center gap-2">
        <span className="text-2xl">💪</span>
        <span className="font-bold text-fg text-lg">UpHealther</span>
      </Link>
      <div className="flex items-center gap-3">
        <ThemeToggle />
        <NotificationBell />
        {user && (
          <span className="text-sm text-fg-subtle hidden sm:block">
            Hi, <span className="font-medium">{user.name}</span>
          </span>
        )}
        <Button variant="ghost" size="sm" onClick={logout}>
          Logout
        </Button>
      </div>
    </nav>
  );
};

export default Navbar;
