import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useEffect } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from 'react-router-dom';
import DashboardPage from '../pages/DashboardPage';
import ActiveUpgradesPage from '../pages/ActiveUpgradesPage';
import PlannedUpgradesPage from '../pages/PlannedUpgradesPage';
import UpgradeDetailsPage from '../pages/UpgradeDetailsPage';
import UpgradeBacklogPage from '../pages/UpgradeBacklogPage';
import DailyCheckinPage from '../pages/DailyCheckinPage';
import { createQueryClient } from './queryClient';
import type {
  ActionTarget,
  CreateUpgradeRequest,
  DashboardDto,
  HealthArea,
  HealthUpgrade,
  UpgradeStatus,
} from '../types';
import type { SaveTrackingConfigRequest } from '../api/trackingConfig';

const getUpgrades = vi.fn();
const getUpgradeById = vi.fn();
const createUpgrade = vi.fn();
const performUpgradeAction = vi.fn();
const planUpgrade = vi.fn();
const updateUpgrade = vi.fn();
const rescheduleUpgrade = vi.fn();
const deleteUpgrade = vi.fn();
const getDashboard = vi.fn();
const saveTrackingConfig = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  getUpgradeById: (...a: unknown[]) => getUpgradeById(...a),
  createUpgrade: (...a: unknown[]) => createUpgrade(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
  planUpgrade: (...a: unknown[]) => planUpgrade(...a),
  updateUpgrade: (...a: unknown[]) => updateUpgrade(...a),
  rescheduleUpgrade: (...a: unknown[]) => rescheduleUpgrade(...a),
  deleteUpgrade: (...a: unknown[]) => deleteUpgrade(...a),
}));
vi.mock('../api/healthAreas', () => ({ getHealthAreas: () => Promise.resolve([AREA]) }));
vi.mock('../api/dashboard', () => ({ getDashboard: (...a: unknown[]) => getDashboard(...a) }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { name: 'Ada Lovelace' } }) }));
vi.mock('../api/progress', () => ({
  getProgressByUpgrade: () => Promise.resolve([]),
  createProgress: vi.fn(),
  getStreak: () => Promise.resolve({ current: 0, longest: 0 }),
}));
vi.mock('../api/reflections', () => ({
  getReflectionsByUpgrade: () => Promise.resolve([]),
  createReflection: vi.fn(),
}));
vi.mock('../api/reminders', () => ({
  getReminders: () => Promise.resolve([]),
  createReminder: vi.fn(),
  deleteReminder: vi.fn(),
}));
vi.mock('../api/trackingConfig', () => ({ saveTrackingConfig: (...a: unknown[]) => saveTrackingConfig(...a) }));

const UPGRADE_ID = 'upgrade-1';
const TITLE = 'Evening walk';
const AREA: HealthArea = { id: 'area-1', userId: 'user-1', name: 'Movement', createdAt: '2026-03-01T09:00:00' };

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
 * Stands in for the API, holding the upgrades every endpoint answers from, so a page that shows
 * something else is showing a cached answer, not a different truth.
 *
 * The dashboard is computed from them as the server computes it, for the sections the tests read: the
 * active and planned lists, and the one area's counts.
 */
function serve(...initial: HealthUpgrade[]) {
  let upgrades = initial;
  const withStatus = (status: UpgradeStatus) => upgrades.filter((u) => u.status === status);
  const change = (id: string, fields: Partial<HealthUpgrade>): HealthUpgrade => {
    upgrades = upgrades.map((u) => (u.id === id ? { ...u, ...fields } : u));
    return upgrades.find((u) => u.id === id)!;
  };

  getUpgrades.mockImplementation((status: UpgradeStatus) => Promise.resolve(withStatus(status)));
  getUpgradeById.mockImplementation((id: string) => Promise.resolve(upgrades.find((u) => u.id === id)));
  getDashboard.mockImplementation((): Promise<DashboardDto> => {
    const filed = upgrades.filter((u) => u.areaId === AREA.id);
    return Promise.resolve({
      activeUpgrades: withStatus('ACTIVE'),
      plannedUpgrades: withStatus('PLANNED'),
      todayUpgrades: [],
      overdueUpgrades: [],
      weeklyCompletionRate: 0,
      streaks: {},
      recentlyCompleted: [],
      areaSummary: [
        {
          areaId: AREA.id,
          areaName: AREA.name,
          totalUpgrades: filed.length,
          activeCount: filed.filter((u) => u.status === 'ACTIVE').length,
          completedCount: filed.filter((u) => u.status === 'COMPLETED').length,
        },
      ],
    });
  });
  createUpgrade.mockImplementation((req: CreateUpgradeRequest) => {
    const created: HealthUpgrade = { ...anUpgrade('IDEA'), ...req, id: `upgrade-${upgrades.length + 1}` };
    upgrades = [...upgrades, created];
    return Promise.resolve(created);
  });
  performUpgradeAction.mockImplementation((id: string, status: ActionTarget) =>
    Promise.resolve(change(id, { status })),
  );
  planUpgrade.mockImplementation((id: string, plannedStartDate: string) =>
    Promise.resolve(change(id, { status: 'PLANNED', plannedStartDate })),
  );
  updateUpgrade.mockImplementation((id: string, req: CreateUpgradeRequest) => Promise.resolve(change(id, req)));
  // BR-2: rescheduling moves the date, and only from ABANDONED changes the status too.
  rescheduleUpgrade.mockImplementation((id: string, newDate: string) => {
    const status = upgrades.find((u) => u.id === id)?.status === 'ABANDONED' ? 'PLANNED' : undefined;
    return Promise.resolve(change(id, { plannedStartDate: newDate, ...(status && { status }) }));
  });
  deleteUpgrade.mockImplementation((id: string) => {
    upgrades = upgrades.filter((u) => u.id !== id);
    return Promise.resolve();
  });
  saveTrackingConfig.mockImplementation((upgradeId: string, req: SaveTrackingConfigRequest) => {
    const trackingConfig = { id: 'config-1', upgradeId, ...req, frequency: req.frequency ?? 'DAILY' } as const;
    change(upgradeId, { trackingConfig });
    return Promise.resolve(trackingConfig);
  });
}

let navigate: NavigateFunction;

/** Holds on to the router's `navigate`, so a test can move between pages the way the navigation does. */
function NavigateHandle() {
  const routerNavigate = useNavigate();
  useEffect(() => {
    navigate = routerNavigate;
  }, [routerNavigate]);
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
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/upgrades/active" element={<ActiveUpgradesPage />} />
          <Route path="/upgrades/planned" element={<PlannedUpgradesPage />} />
          <Route path="/upgrades/backlog" element={<UpgradeBacklogPage />} />
          <Route path="/daily-checkin" element={<DailyCheckinPage />} />
          <Route path="/upgrades/:id" element={<UpgradeDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const goTo = (path: string) =>
  act(() => {
    navigate(path);
  });

/** The figure on the dashboard's summary tile captioned `caption`, once the dashboard has loaded. */
async function tileFigure(caption: string): Promise<string | null | undefined> {
  return (await screen.findByText(caption, { selector: 'div' })).previousElementSibling?.textContent;
}

/** What the dashboard's By Area card says about the one area, once the dashboard has loaded. */
async function areaRow(): Promise<string | null> {
  return within(await screen.findByRole('list', { name: 'Upgrades by area' })).getByRole('listitem').textContent;
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
    createUpgrade.mockReset();
    performUpgradeAction.mockReset();
    planUpgrade.mockReset();
    updateUpgrade.mockReset();
    rescheduleUpgrade.mockReset();
    deleteUpgrade.mockReset();
    saveTrackingConfig.mockReset();
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

  // The upgrade carries its tracking configuration (FR-15), and the check-in picks each entry's input
  // from it; a stale one is scored against the saved configuration, as a miss that cannot be undone.
  it('GivenATrackingTypeChangedOnTheDetailsPage_WhenTheDailyCheckinOpensAgain_ThenItAsksForTheNewKindOfEntry', async () => {
    serve(anUpgrade('ACTIVE'));
    renderApp('/daily-checkin');
    await screen.findByText('Completed today');

    goTo(`/upgrades/${UPGRADE_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Configure' }));
    const dialog = within(screen.getByRole('dialog', { name: 'Configure Tracking' }));
    fireEvent.change(dialog.getByLabelText('Tracking type'), { target: { value: 'NUMERIC' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Configure Tracking' })).toBeNull());

    goTo('/daily-checkin');

    expect(await screen.findByRole('spinbutton')).toBeDefined();
    expect(screen.queryByText('Completed today')).toBeNull();
  });

  // The dashboard counts every upgrade filed under an area, in any state, so a new idea changes it.
  it('GivenAnIdeaCreatedWithAnAreaOnTheBacklog_WhenTheDashboardOpensAgain_ThenItsAreaCountsIt', async () => {
    serve();
    renderApp('/');
    expect(await areaRow()).toContain('0 total');

    goTo('/upgrades/backlog');
    fireEvent.click(await screen.findByRole('button', { name: '+ New Idea' }));
    const dialog = within(screen.getByRole('dialog', { name: 'New Upgrade Idea' }));
    fireEvent.change(dialog.getByLabelText('Title'), { target: { value: 'Stretch at lunch' } });
    await dialog.findByRole('option', { name: AREA.name });
    fireEvent.change(dialog.getByLabelText('Health Area'), { target: { value: AREA.id } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New Upgrade Idea' })).toBeNull());

    goTo('/');

    await waitFor(async () => expect(await areaRow()).toContain('1 total'));
  });

  // #89's writes, made on an upgrade's own page, reach the lists the user goes back to.

  it('GivenAnUpgradeAbandonedOnItsPage_WhenTheActivePageOpensAgain_ThenItIsNoLongerListed', async () => {
    serve(anUpgrade('ACTIVE'));
    renderApp('/upgrades/active');
    await screen.findByRole('button', { name: 'Pause' });

    goTo(`/upgrades/${UPGRADE_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Abandon' }));
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Abandon Upgrade' })).getByRole('button', { name: 'Abandon' }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());

    goTo('/upgrades/active');

    expect(await screen.findByText('No active upgrades')).toBeDefined();
    expect(screen.queryByText(TITLE)).toBeNull();
  });

  it('GivenAnAbandonedUpgradeRescheduledOnItsPage_WhenThePlannedPageOpensAgain_ThenItIsListed', async () => {
    serve(anUpgrade('ABANDONED'));
    renderApp('/upgrades/planned');
    await screen.findByText('No planned upgrades');

    goTo(`/upgrades/${UPGRADE_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Reschedule' }));
    const dialog = within(screen.getByRole('dialog', { name: 'Reschedule Upgrade' }));
    fireEvent.change(dialog.getByLabelText('New start date'), { target: { value: '2026-11-01' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Reschedule Upgrade' })).toBeNull());

    goTo('/upgrades/planned');

    expect(await screen.findByText(TITLE)).toBeDefined();
  });

  it('GivenAnUpgradeEditedOnItsPage_WhenTheActivePageOpensAgain_ThenItShowsTheNewTitle', async () => {
    serve(anUpgrade('ACTIVE'));
    renderApp('/upgrades/active');
    await screen.findByText(TITLE);

    goTo(`/upgrades/${UPGRADE_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Upgrade' }));
    const dialog = within(screen.getByRole('dialog', { name: 'Edit Upgrade' }));
    fireEvent.change(dialog.getByLabelText('Title'), { target: { value: 'Morning walk' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit Upgrade' })).toBeNull());

    goTo('/upgrades/active');

    expect(await screen.findByText('Morning walk')).toBeDefined();
    expect(screen.queryByText(TITLE)).toBeNull();
  });

  it('GivenAnUpgradeDeletedOnItsPage_WhenTheDashboardOpens_ThenItsAreaNoLongerCountsIt', async () => {
    serve({ ...anUpgrade('ACTIVE'), areaId: AREA.id });
    renderApp('/');
    expect(await areaRow()).toContain('1 total');

    goTo(`/upgrades/${UPGRADE_ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Delete Upgrade' })).getByRole('button', { name: 'Delete' }),
    );

    await waitFor(async () => expect(await areaRow()).toContain('0 total'));
  });
});
