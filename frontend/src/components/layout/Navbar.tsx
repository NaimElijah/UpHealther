import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import Button from '../ui/Button';
import NotificationBell from '../notifications/NotificationBell';
import ThemeToggle from '../theme/ThemeToggle';
import NavMenu from './NavMenu';

/**
 * Top bar: the phone menu (below `md` only), brand link home, the theme toggle, the notification bell,
 * the signed-in user's name, and logout — with a strip beneath it when a sign-out fails.
 */
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

  // The failure is a strip under the bar rather than an item in it: the bar is a fixed h-16 row with no
  // room left at 320px, and a message that only fits on a wide screen goes unsaid exactly where a
  // shared device is likeliest.
  return (
    <header className="sticky top-0 z-30">
      <nav className="bg-surface border-b border-line h-16 flex items-center gap-4 px-4 sm:px-6 justify-between">
        <div className="flex items-center gap-2 min-w-0">
          <NavMenu />
          {/* The brand gives way to the controls. Below 360px the menu, theme switch, bell and Logout
              leave it no room, so it is not rendered at all: clipped to nothing it would still be a
              focusable link with an invisible focus ring (the menu's Dashboard entry stands in for it).
              360px is where its emoji first fits on a phone; overflow-hidden covers the few pixels a
              desktop scrollbar takes just above that, so the emoji is clipped rather than painted over
              the theme switch. */}
          <Link to="/dashboard" className="hidden min-[360px]:flex items-center gap-2 min-w-0 overflow-hidden">
            <span className="text-2xl shrink-0" aria-hidden="true">💪</span>
            <span className="font-bold text-fg text-lg truncate">UpHealther</span>
          </Link>
        </div>
        <div className="flex items-center gap-2 sm:gap-3 shrink-0">
          <ThemeToggle />
          <NotificationBell />
          {user && (
            <span className="text-sm text-fg-subtle hidden sm:block max-w-[12rem] truncate">
              Hi, <span className="font-medium">{user.name}</span>
            </span>
          )}
          <Button variant="ghost" size="sm" onClick={handleSignOut} className="shrink-0">
            Logout
          </Button>
        </div>
      </nav>
      {signOutFailed && (
        <p role="alert" className="bg-danger-soft border-b border-danger-line px-4 sm:px-6 py-2 text-sm text-danger-fg">
          Could not sign out — you are still signed in.
        </p>
      )}
    </header>
  );
};

export default Navbar;
