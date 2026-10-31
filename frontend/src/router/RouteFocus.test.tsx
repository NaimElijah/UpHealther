import { describe, it, expect } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { Link, MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import RouteFocus from './RouteFocus';
import PageHeading from '../components/ui/PageHeading';
import NavMenu from '../components/layout/NavMenu';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import type { User } from '../types';

const USER: User = {
  id: 'user-1',
  name: 'Someone',
  email: 'someone@example.com',
  role: 'USER',
  createdAt: '2026-03-15T09:00:00',
};

/** The browser's Back, which a test cannot press in a `MemoryRouter`. */
const BackButton = () => {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(-1)}>
      Back
    </button>
  );
};

/** A page whose heading is there from its first render, with a link to the other pages, and Back. */
const ReadyPage = ({ title }: { title: string }) => (
  <>
    <PageHeading>{title}</PageHeading>
    <Link to="/dashboard">To the dashboard</Link>
    <Link to="/slow">To the slow page</Link>
    <BackButton />
  </>
);

/**
 * A page that shows no heading until its data arrives, as a page behind a spinner does. `arrive` is
 * captured so the test decides when.
 */
let arrive: () => void = () => {};
const SlowPage = () => {
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    arrive = () => setLoaded(true);
  }, []);
  return (
    <>
      {loaded && <PageHeading>Slow page</PageHeading>}
      <button type="button">Somewhere else</button>
    </>
  );
};

function renderApp() {
  const ctx: AuthContextType = {
    user: USER,
    isLoading: false,
    isAuthenticated: true,
    login: async () => {},
    register: async () => {},
    logout: async () => {},
  };
  return render(
    <AuthContext.Provider value={ctx}>
      <MemoryRouter initialEntries={['/health-areas']}>
        <NavMenu />
        <Routes>
          <Route path="/health-areas" element={<ReadyPage title="Health Areas" />} />
          <Route path="/dashboard" element={<ReadyPage title="Dashboard" />} />
          <Route path="/slow" element={<SlowPage />} />
        </Routes>
        <RouteFocus />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/**
 * Following a link moves focus to the new page's heading. It stayed on the link, or went back to the
 * phone menu's button, so a keyboard user had to tab through the whole shell again, and a screen reader
 * announced nothing of the page that had opened (WCAG 2.4.3, 4.1.3, finding X9.3, ADR-083).
 */
describe('RouteFocus', () => {
  it('GivenTheAppHasJustOpened_WhenItRenders_ThenFocusIsLeftWhereTheBrowserPutIt', () => {
    renderApp();

    expect(document.activeElement).toBe(document.body);
  });

  it('GivenALinkIsFollowed_WhenTheNewPageRenders_ThenItsHeadingHasFocus', () => {
    renderApp();
    const link = screen.getByRole('link', { name: 'To the dashboard' });
    link.focus();

    fireEvent.click(link);

    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Dashboard' }));
  });

  // Back to the first entry restores the first entry's location key, so a check against the first key
  // instead of the one before would take it for no change at all (review finding 18 on #407).
  it('GivenALinkWasFollowedFromTheFirstEntry_WhenBackIsPressed_ThenTheFirstPagesHeadingHasFocus', () => {
    renderApp();
    fireEvent.click(screen.getByRole('link', { name: 'To the dashboard' }));
    // Pressed from the page, so focus starts on Back: both routes draw `ReadyPage`, which React keeps, h1
    // and all, so a heading that already had focus would keep it whether or not anything moved it.
    const back = screen.getByRole('button', { name: 'Back' });
    back.focus();

    fireEvent.click(back);

    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Health Areas' }));
  });

  it('GivenThePhoneMenu_WhenOneOfItsLinksIsFollowed_ThenFocusLandsOnTheNewPagesHeadingNotTheMenuButton', () => {
    renderApp();
    fireEvent.click(screen.getByRole('button', { name: 'Open navigation' }));

    fireEvent.click(screen.getByRole('link', { name: /Dashboard/ }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Dashboard' }));
  });

  it('GivenANewPageStillLoading_WhenItsHeadingAppears_ThenTheHeadingTakesFocus', () => {
    renderApp();
    const link = screen.getByRole('link', { name: 'To the slow page' });
    link.focus();
    fireEvent.click(link);

    act(() => arrive());

    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Slow page' }));
  });

  it('GivenFocusWasMovedWhileTheNewPageLoaded_WhenItsHeadingAppears_ThenFocusStaysWhereTheUserPutIt', () => {
    renderApp();
    const link = screen.getByRole('link', { name: 'To the slow page' });
    link.focus();
    fireEvent.click(link);
    const elsewhere = screen.getByRole('button', { name: 'Somewhere else' });
    elsewhere.focus();

    act(() => arrive());

    expect(document.activeElement).toBe(elsewhere);
  });
});
