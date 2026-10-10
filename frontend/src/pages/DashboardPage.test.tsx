import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import DashboardPage from './DashboardPage';
import type { AreaSummary, DashboardDto, HealthUpgrade } from '../types';

const getDashboard = vi.fn();
const performUpgradeAction = vi.fn();

vi.mock('../api/dashboard', () => ({ getDashboard: (...a: unknown[]) => getDashboard(...a) }));
vi.mock('../api/upgrades', () => ({ performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a) }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ user: { name: 'Ada Lovelace' } }) }));

function aDashboard(areaSummary: AreaSummary[]): DashboardDto {
  return {
    activeUpgrades: [],
    plannedUpgrades: [],
    todayUpgrades: [],
    overdueUpgrades: [],
    weeklyCompletionRate: 0,
    streaks: {},
    recentlyCompleted: [],
    areaSummary,
  };
}

function anActiveUpgrade(id: string, title: string): HealthUpgrade {
  return {
    id,
    userId: 'user-1',
    title,
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'MEDIUM',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  };
}

/**
 * A dashboard with these active upgrades and this streak map, shaped the way the server sends it: one
 * key per active upgrade, zeros included.
 */
function aDashboardWithStreaks(activeUpgrades: HealthUpgrade[], streaks: Record<string, number>): DashboardDto {
  return { ...aDashboard([]), activeUpgrades, streaks };
}

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

/**
 * Lets every promise already started run on: a mutation calls its function a microtask after `mutate`,
 * so a check that nothing more was sent is only meaningful once that has had its chance.
 */
const settled = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The By Area card's rows, once the dashboard has loaded. */
async function findAreaRows(): Promise<HTMLElement[]> {
  return within(await screen.findByRole('list', { name: 'Upgrades by area' })).getAllByRole('listitem');
}

/** The figure on the summary tile captioned `caption`, once the dashboard has loaded. */
async function tileFigure(caption: string): Promise<string | null | undefined> {
  return (await screen.findByText(caption, { selector: 'div' })).previousElementSibling?.textContent;
}

/** The Current Streaks card's tiles, once the dashboard has loaded. */
async function findStreakTiles(): Promise<HTMLElement[]> {
  return within(await screen.findByRole('list', { name: 'Current streaks' })).getAllByRole('listitem');
}

/** The one row that names `areaName`. */
function rowFor(rows: HTMLElement[], areaName: string): HTMLElement {
  const row = rows.find((r) => r.firstElementChild?.textContent === areaName);
  if (!row) throw new Error(`No row for area "${areaName}"`);
  return row;
}

/**
 * FR-27 — among what the dashboard shows, per-area counts.
 *
 * The server sends one summary per health area the user owns, including areas nothing is filed
 * under yet, and the page shows each of them: a zero is still a count.
 */
describe('DashboardPage', () => {
  beforeEach(() => {
    getDashboard.mockReset();
    performUpgradeAction.mockReset();
  });

  it('GivenAreasInTheSummary_WhenTheDashboardRenders_ThenEachAreaShowsItsCounts', async () => {
    getDashboard.mockResolvedValue(aDashboard([
      { areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 4, activeCount: 2, completedCount: 1 },
      { areaId: 'area-2', areaName: 'Nutrition', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
    ]));

    renderPage();

    const areas = await findAreaRows();
    expect(areas).toHaveLength(2);
    expect(rowFor(areas, 'Sleep').textContent).toContain('2 active · 1 completed · 4 total');
    expect(rowFor(areas, 'Nutrition').textContent).toContain('0 active · 0 completed · 0 total');
  });

  it('GivenAreasInNoParticularOrder_WhenTheDashboardRenders_ThenTheyAreListedByName', async () => {
    // The server's area query has no ORDER BY, so the same areas can arrive in a different order from
    // one load to the next. Sorting here keeps the rows from shuffling between refreshes.
    getDashboard.mockResolvedValue(aDashboard([
      { areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
      { areaId: 'area-2', areaName: 'Movement', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
      { areaId: 'area-3', areaName: 'Nutrition', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
    ]));

    renderPage();

    const names = (await findAreaRows()).map((row) => row.firstElementChild?.textContent);
    expect(names).toEqual(['Movement', 'Nutrition', 'Sleep']);
  });

  it('GivenAreasInTheSummary_WhenTheDashboardRenders_ThenTheListDeclaresItsRoleExplicitly', async () => {
    // Tailwind's preflight strips list-style, and WebKit then stops exposing a <ul> as a list, which
    // takes its aria-label with it. jsdom does not copy that behaviour, so the implicit role would pass
    // the query above either way. The explicit attribute is what VoiceOver actually relies on.
    getDashboard.mockResolvedValue(aDashboard([
      { areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 1, activeCount: 1, completedCount: 0 },
    ]));

    renderPage();

    const list = await screen.findByRole('list', { name: 'Upgrades by area' });
    expect(list.getAttribute('role')).toBe('list');
  });

  it('GivenNoHealthAreas_WhenTheDashboardRenders_ThenNoAreaSectionIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    await screen.findByText('Weekly Completion Rate');
    expect(screen.queryByRole('list', { name: 'Upgrades by area' })).toBeNull();
  });

  // FR-27 (#118) — among what the dashboard shows, streaks. The server sends one per active upgrade,
  // zeros included, keyed by id; the page shows only the ones that are running, each under its title.

  it('GivenThreeActiveUpgradesAndOneRunningStreak_WhenTheDashboardRenders_ThenTheStreaksTileCountsOne', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks(
      [anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk'), anActiveUpgrade('u-3', 'No sugar')],
      { 'u-1': 5, 'u-2': 0, 'u-3': 0 },
    ));

    renderPage();

    expect(await tileFigure('Active')).toBe('3');
    expect(await tileFigure('Streaks')).toBe('1');
  });

  it('GivenARunningStreak_WhenTheDashboardRenders_ThenItsTileNamesTheUpgrade', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks(
      [anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')],
      { 'u-1': 5, 'u-2': 0 },
    ));

    renderPage();

    const tiles = await findStreakTiles();
    expect(tiles).toHaveLength(1);
    expect(within(tiles[0]).getByText('Cold showers')).toBeDefined();
    expect(within(tiles[0]).getByText('5')).toBeDefined();
    expect(within(tiles[0]).getByText('days')).toBeDefined();
  });

  it('GivenAOneDayStreak_WhenTheDashboardRenders_ThenItReadsOneDay', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks([anActiveUpgrade('u-1', 'Cold showers')], { 'u-1': 1 }));

    renderPage();

    const [tile] = await findStreakTiles();
    expect(within(tile).getByText('day')).toBeDefined();
  });

  it('GivenNoRunningStreak_WhenTheDashboardRenders_ThenNoStreakSectionIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks(
      [anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')],
      { 'u-1': 0, 'u-2': 0 },
    ));

    renderPage();

    expect(await tileFigure('Streaks')).toBe('0');
    expect(screen.queryByRole('list', { name: 'Current streaks' })).toBeNull();
  });

  // NFR-30 (#96) — a status change from a card that fails says so, with the trace id that finds it.
  // Before, the handler was an un-awaited async function with no catch: a refusal became an unhandled
  // rejection and the card simply stayed as it was.

  it('GivenTheApiRefusesAPause_WhenItIsPressedOnACard_ThenTheCardSaysWhyWithTheReference', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks([anActiveUpgrade('u-1', 'Cold showers')], { 'u-1': 0 }));
    performUpgradeAction.mockRejectedValue(
      apiFailure(409, { status: 409, message: 'Resource was modified concurrently. Please retry.', traceId: 'trace-71' }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That change did not go through.');
    expect(alert.textContent).toContain('Resource was modified concurrently. Please retry.');
    expect(alert.textContent).toContain('trace-71');
  });

  it('GivenAChangeInFlight_WhenAnotherCardIsPressed_ThenNothingMoreIsSent', async () => {
    getDashboard.mockResolvedValue(aDashboardWithStreaks(
      [anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')],
      { 'u-1': 0, 'u-2': 0 },
    ));
    performUpgradeAction.mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByText('Evening walk');

    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[0]);
    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[1]);

    await settled();
    expect(performUpgradeAction).toHaveBeenCalledTimes(1);
  });

  it('GivenAnUpgradeListedInTwoSections_WhenAPauseIsRefusedInOne_ThenTheRefusalIsShownOnceWithThatCard', async () => {
    // Today's Health Moves and Active Upgrades can both list one upgrade. The refusal belongs with the card
    // that was pressed, further down the page than the greeting, and only once.
    const upgrade = anActiveUpgrade('u-1', 'Cold showers');
    getDashboard.mockResolvedValue({ ...aDashboardWithStreaks([upgrade], { 'u-1': 0 }), todayUpgrades: [upgrade] });
    performUpgradeAction.mockRejectedValue(
      apiFailure(409, { status: 409, message: 'Resource was modified concurrently. Please retry.', traceId: 'trace-72' }),
    );
    renderPage();
    await screen.findByText('Active Upgrades');
    const [, inActiveSection] = screen.getAllByText('Cold showers');

    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[1]);

    const alert = await screen.findByRole('alert');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect((inActiveSection.compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0).toBe(true);
  });
});
