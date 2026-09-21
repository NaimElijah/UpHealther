import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import Button from '../ui/Button';
import NotificationBell from '../notifications/NotificationBell';
import ThemeToggle from '../theme/ThemeToggle';

/** Top bar: brand link home, the theme toggle, the notification bell, the signed-in user's name, and logout. */
const Navbar: React.FC = () => {
  const { user, logout } = useAuth();
  const [signOutFailed, setSignOutFailed] = useState(false);

  /**
   * Signs out, and says so when it could not.
   *
   * A failed sign-out must be visible. The refresh cookie is still in the browser, so the session is
   * still live and the next page load would sign the user straight back in — on a shared machine,
   * after they believed they had left.
   */
  const handleSignOut = async () => {
    setSignOutFailed(false);
    try {
      await logout();
    } catch {
      setSignOutFailed(true);
    }
  };

  return (
    <nav className="bg-surface border-b border-line h-16 flex items-center gap-4 px-4 sm:px-6 justify-between sticky top-0 z-30">
      <Link to="/dashboard" className="flex items-center gap-2 min-w-0">
        <span className="text-2xl shrink-0" aria-hidden="true">💪</span>
        <span className="font-bold text-fg text-lg truncate">UpHealther</span>
      </Link>
      <div className="flex items-center gap-2 sm:gap-3 shrink-0">
        <ThemeToggle />
        <NotificationBell />
        {user && (
          <span className="text-sm text-fg-subtle hidden sm:block max-w-[12rem] truncate">
            Hi, <span className="font-medium">{user.name}</span>
          </span>
        )}
        {signOutFailed && (
          <span role="alert" className="text-sm text-danger-fg hidden sm:block">
            Could not sign out — you are still signed in.
          </span>
        )}
        <Button variant="ghost" size="sm" onClick={handleSignOut} className="shrink-0">
          Logout
        </Button>
      </div>
    </nav>
  );
};

export default Navbar;
