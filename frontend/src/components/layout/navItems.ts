import type { UserRole } from '../../types';

/** One navigation entry: where it goes, what it is called, and the emoji standing in for an icon. */
export interface NavItem {
  to: string;
  label: string;
  icon: string;
}

/**
 * The primary navigation, in the order the app is meant to be used: plan, then run, then review.
 *
 * This list is the navigation's whole content — adding a page here is what puts it in the sidebar and
 * the phone menu alike, and the route itself still has to be registered in the router. It lives in a
 * `.ts` module because `react-refresh/only-export-components` refuses a non-component export from a
 * `.tsx` file.
 */
const navItems: readonly NavItem[] = [
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
 * Entries only an administrator is shown.
 *
 * Hiding it is tidiness, not security: the page and every request behind it are refused by the
 * server for anyone else, whatever this list happens to render.
 */
const adminNavItems: readonly NavItem[] = [
  { to: '/admin/users', label: 'Accounts', icon: '🛡️' },
];

/**
 * The entries a user with the given role is offered.
 *
 * The administrator's entries are appended, never substituted: an administrator is a user first. With
 * no role yet — while the session is being restored — only the ordinary entries are offered, so an
 * entry most people cannot use never flashes up and disappears.
 *
 * @param role the signed-in user's role, or `undefined` while there is no user
 * @returns the entries to render, in order
 */
export function navItemsFor(role: UserRole | undefined): readonly NavItem[] {
  return role === 'ADMIN' ? [...navItems, ...adminNavItems] : navItems;
}
