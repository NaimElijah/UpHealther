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

    const areas = within(await screen.findByRole('list', { name: 'Upgrades by area' })).getAllByRole('listitem');
    expect(areas).toHaveLength(2);
    expect(areas[0].textContent).toContain('Sleep');
    expect(areas[0].textContent).toContain('2 active · 1 completed · 4 total');
    expect(areas[1].textContent).toContain('Nutrition');
    expect(areas[1].textContent).toContain('0 active · 0 completed · 0 total');
  });

  it('GivenNoHealthAreas_WhenTheDashboardRenders_ThenNoAreaSectionIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    await screen.findByText('Weekly Completion Rate');
    expect(screen.queryByRole('list', { name: 'Upgrades by area' })).toBeNull();
  });
});
