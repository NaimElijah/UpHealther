import { describe, it, expect } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from 'react-router-dom';
import NavMenu from './NavMenu';
import { AuthContext, type AuthContextType } from '../../contexts/authContextValue';
import type { User } from '../../types';

const ADMIN: User = {
  id: 'admin-1',
  name: 'An Administrator',
  email: 'admin@example.com',
  role: 'ADMIN',
  createdAt: '2026-03-15T09:00:00',
};

const ORDINARY: User = { ...ADMIN, id: 'user-1', email: 'someone@example.com', role: 'USER' };

/** Renders where the router currently is, so a test can see that a link was followed. */
const LocationProbe = () => <output aria-label="location">{useLocation().pathname}</output>;

/**
 * The router's `navigate`, captured so a test can move the location from outside the menu — the
 * stand-in for the browser's Back button, which is how a phone user most often dismisses an overlay.
 */
let navigate: NavigateFunction;
const NavigateHandle = () => {
  navigate = useNavigate();
  return null;
};

function renderMenu(user: User) {
  const ctx: AuthContextType = {
    user,
    isLoading: false,
    isAuthenticated: true,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
  };
  return render(
    <AuthContext.Provider value={ctx}>
      <MemoryRouter initialEntries={['/progress-history', '/dashboard']} initialIndex={1}>
        <NavigateHandle />
        <NavMenu />
        <Routes>
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function openMenu() {
  fireEvent.click(screen.getByRole('button', { name: 'Open navigation' }));
  return screen.getByRole('dialog', { name: 'Menu' });
}

/**
 * The navigation a window narrower than the sidebar's breakpoint gets (#97).
 *
 * jsdom has no layout, so which of the two navigations a given width shows cannot be observed here;
 * that is checked in a browser (NFR-20, §6). What these pin is that the menu offers every page the
 * sidebar does — the pages a phone could otherwise not reach at all — and gets out of the way once one
 * is chosen.
 */
describe('NavMenu', () => {
  it('GivenTheMenuIsClosed_WhenItsButtonIsPressed_ThenADialogListsEveryPage', () => {
    renderMenu(ORDINARY);
    const button = screen.getByRole('button', { name: 'Open navigation' });
    expect(button.getAttribute('aria-expanded')).toBe('false');

    const dialog = openMenu();

    expect(button.getAttribute('aria-expanded')).toBe('true');
    const hrefs = within(dialog)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'));
    expect(hrefs).toEqual([
      '/dashboard',
      '/health-areas',
      '/upgrades/backlog',
      '/upgrades/planned',
      '/upgrades/active',
      '/daily-checkin',
      '/progress-history',
      '/notifications',
    ]);
  });

  it('GivenTheMenuIsOpen_WhenALinkIsFollowed_ThenThePageChangesAndTheMenuCloses', () => {
    renderMenu(ORDINARY);
    const dialog = openMenu();

    fireEvent.click(within(dialog).getByRole('link', { name: /Health Areas/ }));

    expect(screen.getByRole('status', { name: 'location' }).textContent).toBe('/health-areas');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('GivenTheMenuIsOpen_WhenTheBrowserGoesBack_ThenTheMenuCloses', () => {
    renderMenu(ORDINARY);
    openMenu();

    act(() => navigate(-1));

    expect(screen.getByRole('status', { name: 'location' }).textContent).toBe('/progress-history');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('GivenTheMenuWasClosedByFollowingALink_WhenTheBrowserGoesBackToWhereItOpened_ThenItStaysClosed', () => {
    // Going back returns to the same history entry, so a menu remembered against that entry would
    // reopen there by itself.
    renderMenu(ORDINARY);
    fireEvent.click(within(openMenu()).getByRole('link', { name: /Health Areas/ }));

    act(() => navigate(-1));

    expect(screen.getByRole('status', { name: 'location' }).textContent).toBe('/dashboard');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('GivenTheMenuIsOpen_WhenALinkIsCtrlClicked_ThenThisTabStaysAndSoDoesTheMenu', () => {
    // A modified click opens the page in another tab and leaves this one where it was, so there is
    // nothing to get out of the way of.
    renderMenu(ORDINARY);
    const dialog = openMenu();

    fireEvent.click(within(dialog).getByRole('link', { name: /Health Areas/ }), { ctrlKey: true });

    expect(screen.getByRole('status', { name: 'location' }).textContent).toBe('/dashboard');
    expect(screen.getByRole('dialog', { name: 'Menu' })).toBeDefined();
  });

  it('GivenAnAdministrator_WhenTheMenuIsOpened_ThenTheAccountsLinkIsThere', () => {
    renderMenu(ADMIN);

    const link = within(openMenu()).getByRole('link', { name: /Accounts/ });

    expect(link.getAttribute('href')).toBe('/admin/users');
  });

  it('GivenAnOrdinaryUser_WhenTheMenuIsOpened_ThenThereIsNoAccountsLink', () => {
    renderMenu(ORDINARY);

    expect(within(openMenu()).queryByRole('link', { name: /Accounts/ })).toBeNull();
  });
});
