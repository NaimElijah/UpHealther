import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ProgressHistoryPage from './ProgressHistoryPage';
import type { HealthUpgrade, ProgressEntry } from '../types';

const getWeekProgress = vi.fn();
const getUpgrades = vi.fn();

vi.mock('../api/progress', () => ({ getWeekProgress: (...a: unknown[]) => getWeekProgress(...a) }));
vi.mock('../api/upgrades', () => ({ getUpgrades: (...a: unknown[]) => getUpgrades(...a) }));

const UPGRADE_ID = 'upgrade-1';

function anUpgrade(): HealthUpgrade {
  return {
    id: UPGRADE_ID,
    userId: 'user-1',
    title: 'Morning stretch',
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'EASY',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  };
}

/** Built the way the API sends an entry: a field the entry does not use is `null`, not missing. */
function anEntry(values: Partial<ProgressEntry>): ProgressEntry {
  return {
    id: 'p-1',
    upgradeId: UPGRADE_ID,
    userId: 'user-1',
    date: '2026-03-12',
    completed: null,
    numericValue: null,
    unit: null,
    rating: null,
    note: null,
    createdAt: '2026-03-12T20:00:00',
    ...values,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ProgressHistoryPage />
    </QueryClientProvider>,
  );
}

/** FR-21 (#117) — the week's entries show only the values they carry. */
describe('ProgressHistoryPage', () => {
  beforeEach(() => {
    getWeekProgress.mockReset();
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([anUpgrade()]);
  });

  it('GivenAnEntryWithNoVerdict_WhenTheWeekIsListed_ThenItIsNotMarkedMissed', async () => {
    getWeekProgress.mockResolvedValue([anEntry({ note: 'stretched a little' })]);

    renderPage();

    await screen.findByText('stretched a little');
    expect(screen.queryByText(/Missed/)).toBeNull();
    expect(screen.queryByText(/Done/)).toBeNull();
  });

  it('GivenARatedEntry_WhenTheWeekIsListed_ThenItShowsOneStarPerPoint', async () => {
    getWeekProgress.mockResolvedValue([anEntry({ completed: true, rating: 3 })]);

    renderPage();

    expect(await screen.findByText('⭐⭐⭐')).toBeDefined();
  });

  it('GivenAnEntryDatedADay_WhenTheWeekIsListed_ThenItShowsThatDay', async () => {
    // FR-18 (#95). Parsed as UTC midnight, 12 March is the evening of the 11th in Los Angeles, where
    // the suite runs, and was shown as Wednesday the 11th.
    getWeekProgress.mockResolvedValue([anEntry({ date: '2026-03-12', completed: true })]);

    renderPage();

    expect(await screen.findByText('Thu, Mar 12')).toBeDefined();
  });
});
