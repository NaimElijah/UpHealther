import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from 'react-router-dom';
import DashboardPage from '../pages/DashboardPage';
import ActiveUpgradesPage from '../pages/ActiveUpgradesPage';
import PlannedUpgradesPage from '../pages/PlannedUpgradesPage';
import UpgradeDetailsPage from '../pages/UpgradeDetailsPage';
import UpgradeBacklogPage from '../pages/UpgradeBacklogPage';
import { createQueryClient } from './queryClient';
import type { ActionTarget, DashboardDto, HealthUpgrade, UpgradeStatus } from '../types';

const getUpgrades = vi.fn();
const getUpgradeById = vi.fn();
const performUpgradeAction = vi.fn();
const planUpgrade = vi.fn();
const getDashboard = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  getUpgradeById: (...a: unknown[]) => getUpgradeById(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
  planUpgrade: (...a: unknown[]) => planUpgrade(...a),
  createUpgrade: vi.fn(),
}));
vi.mock('../api/healthAreas', () => ({ getHealthAreas: () => Promise.resolve([]) }));
vi.mock('../api/dashboard', () => ({ getDashboard: (...a: unknown[]) => getDashboard(...a) }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { name: 'Ada Lovelace' } }) }));
vi.mock('../api/progress', () => ({
  getProgressByUpgrade: () => Promise.resolve([]),
  createProgress: vi.fn(),
  getStreak: () => Promise.resolve({ current: 0, longest: 0 }),
}));
vi.mock('../api/reflections', () => ({ getReflectionsByUpgrade: () => Promise.resolve([]), createReflection: vi.fn() }));
vi.mock('../api/reminders', () => ({
  getReminders: () => Promise.resolve([]),
  createReminder: vi.fn(),
  deleteReminder: vi.fn(),
}));
vi.mock('../api/trackingConfig', () => ({ saveTrackingConfig: vi.fn() }));

const UPGRADE_ID = 'upgrade-1';
const TITLE = 'Evening walk';

function anUpgrade(status: UpgradeStatus): HealthUpgrade {
  return {
    id: UPGRADE_ID,
    userId: 'user-1',
    title: TITLE,
    type: 'HABIT',
    status,
    difficulty: 'MEDIUM',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  };
}

/**
 * Stands in for the API with one upgrade whose status every endpoint agrees on, so a page that shows a
 * different status is showing a cached answer, not a different truth.
 */
function serve(initial: HealthUpgrade) {
  let upgrade = initial;
  getUpgrades.mockImplementation((status: UpgradeStatus) => Promise.resolve(upgrade.status === status ? [upgrade] : []));
  getUpgradeById.mockImplementation(() => Promise.resolve(upgrade));
  getDashboard.mockImplementation((): Promise<DashboardDto> => Promise.resolve({
    activeUpgrades: upgrade.status === 'ACTIVE' ? [upgrade] : [],
    plannedUpgrades: upgrade.status === 'PLANNED' ? [upgrade] : [],
    todayUpgrades: [],
    overdueUpgrades: [],
    weeklyCompletionRate: 0,
    streaks: {},
    recentlyCompleted: [],
    areaSummary: [],
  }));
  performUpgradeAction.mockImplementation((_id: string, status: ActionTarget) => {
    upgrade = { ...upgrade, status };
    return Promise.resolve(upgrade);
  });
  planUpgrade.mockImplementation((_id: string, plannedStartDate: string) => {
    upgrade = { ...upgrade, status: 'PLANNED', plannedStartDate };
    return Promise.resolve(upgrade);
  });
}

let navigate: NavigateFunction;

/** Holds on to the router's `navigate`, so a test can move between pages the way the navigation does. */
function NavigateHandle() {
  navigate = useNavigate();
  return null;
}

/**
 * Renders the pages under one cache built as the app builds it, so each page opened again within the
 * staleness window renders from whatever that cache still holds.
 */
function renderApp(path: string) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <NavigateHandle />
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/upgrades/active" element={<ActiveUpgradesPage />} />
          <Route path="/upgrades/planned" element={<PlannedUpgradesPage />} />
          <Route path="/upgrades/backlog" element={<UpgradeBacklogPage />} />
          <Route path="/upgrades/:id" element={<UpgradeDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const goTo = (path: string) => act(() => { navigate(path); });

/** The figure on the dashboard's summary tile captioned `caption`, once the dashboard has loaded. */
async function tileFigure(caption: string): Promise<string | null | undefined> {
  return (await screen.findByText(caption, { selector: 'div' })).previousElementSibling?.textContent;
}

/**
 * FR-12 (#189) — a change made on one page reaches every other page that shows the upgrade.
 *
 * Each page reads the upgrade under its own key, and the cache keeps an answer fresh for thirty
 * seconds. A change that refreshed only the page it was made on left the others showing a state the
 * upgrade had already left, with buttons for transitions the server would now refuse.
 */
describe('a change to an upgrade', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    getUpgradeById.mockReset();
    performUpgradeAction.mockReset();
    planUpgrade.mockReset();
    getDashboard.mockReset();
  });

  it('GivenAnUpgradePausedOnTheDashboard_WhenTheActivePageOpensAgain_ThenItIsListedUnderPaused', async () => {
    serve(anUpgrade('ACTIVE'));
    renderApp('/upgrades/active');
    await screen.findByRole('button', { name: 'Pause' });

    goTo('/');
    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    await screen.findByText('No active upgrades');

    goTo('/upgrades/active');

    const paused = await screen.findByRole('region', { name: 'Paused' });
    expect(paused.textContent).toContain(TITLE);
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
  });

  it('GivenAnUpgradePausedOnTheActivePage_WhenTheDashboardOpensAgain_ThenItShowsTheChange', async () => {
    serve(anUpgrade('ACTIVE'));
    renderApp('/');
    await screen.findByRole('button', { name: 'Pause' });

    goTo('/upgrades/active');
    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    await screen.findByRole('region', { name: 'Paused' });

    goTo('/');

    expect(await screen.findByText('No active upgrades')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
  });

  it('GivenAnUpgradeActivatedOnThePlannedPage_WhenItsDetailsPageOpens_ThenItShowsActive', async () => {
    serve(anUpgrade('PLANNED'));
    renderApp(`/upgrades/${UPGRADE_ID}`);
    await screen.findByText('Planned');

    goTo('/upgrades/planned');
    fireEvent.click(await screen.findByRole('button', { name: 'Activate' }));
    await screen.findByText('No planned upgrades');

    goTo(`/upgrades/${UPGRADE_ID}`);

    expect(await screen.findByText('Active')).toBeDefined();
    await waitFor(() => expect(screen.queryByText('Planned')).toBeNull());
  });

  it('GivenAnIdeaPlannedOnTheBacklog_WhenTheDashboardOpensAgain_ThenItCountsItAsPlanned', async () => {
    serve(anUpgrade('IDEA'));
    renderApp('/');
    expect(await tileFigure('Planned')).toBe('0');

    goTo('/upgrades/backlog');
    fireEvent.click(await screen.findByRole('button', { name: 'Plan' }));
    const dialog = within(screen.getByRole('dialog', { name: 'Plan Upgrade' }));
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Plan Upgrade' })).toBeNull());

    goTo('/');

    await waitFor(async () => expect(await tileFigure('Planned')).toBe('1'));
  });
});
