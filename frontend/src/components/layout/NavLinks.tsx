import React from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { navItemsFor } from './navItems';

/**
 * The signed-in user's navigation entries as links, with the current route highlighted. Rendered by
 * the sidebar on a wide window and by the phone menu on a narrow one, so the two cannot drift apart.
 *
 * The landmark is named "Main", so a screen reader's landmark list says which navigation it is. It is the
 * app's only one: the navbar around the phone menu's button is not a `<nav>`.
 */
const NavLinks: React.FC = () => {
  const { user } = useAuth();

  return (
    <nav aria-label="Main" className="py-4">
      {navItemsFor(user?.role).map((item) => (
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
          <span aria-hidden="true">{item.icon}</span>
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  );
};

export default NavLinks;
