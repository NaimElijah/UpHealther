import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import DashboardPage from './DashboardPage';
import type { AreaSummary, DashboardDto } from '../types';

const getDashboard = vi.fn();

vi.mock('../api/dashboard', () => ({ getDashboard: (...a: unknown[]) => getDashboard(...a) }));
vi.mock('../api/upgrades', () => ({ performUpgradeAction: vi.fn() }));
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

  it('GivenNoHealthAreas_WhenTheDashboardRenders_ThenNoAreaSectionIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    await screen.findByText('Weekly Completion Rate');
    expect(screen.queryByRole('list', { name: 'Upgrades by area' })).toBeNull();
  });
});
