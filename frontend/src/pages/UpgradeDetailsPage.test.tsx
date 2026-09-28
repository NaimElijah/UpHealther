import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from 'react-router-dom';
import UpgradeDetailsPage from './UpgradeDetailsPage';
import type { HealthUpgrade, ProgressEntry, Reflection } from '../types';

const getUpgradeById = vi.fn();
const getProgressByUpgrade = vi.fn();
const createProgress = vi.fn();
const getReflectionsByUpgrade = vi.fn();

vi.mock('../api/upgrades', () => ({ getUpgradeById: (...a: unknown[]) => getUpgradeById(...a) }));
vi.mock('../api/progress', () => ({
  getProgressByUpgrade: (...a: unknown[]) => getProgressByUpgrade(...a),
  createProgress: (...a: unknown[]) => createProgress(...a),
  getStreak: () => Promise.resolve({ current: 0, longest: 0 }),
}));
vi.mock('../api/reflections', () => ({
  getReflectionsByUpgrade: (...a: unknown[]) => getReflectionsByUpgrade(...a),
  createReflection: vi.fn(),
}));
vi.mock('../api/reminders', () => ({
  getReminders: () => Promise.resolve([]),
  createReminder: vi.fn(),
  deleteReminder: vi.fn(),
}));
vi.mock('../api/trackingConfig', () => ({ saveTrackingConfig: vi.fn() }));

const UPGRADE_ID = 'upgrade-1';
const OTHER_UPGRADE_ID = 'upgrade-2';

function anUpgrade(id = UPGRADE_ID, title = 'Cold showers'): HealthUpgrade {
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

/** Built the way the API sends an entry: a field the entry does not use is `null`, not missing. */
function anEntry(id: string, date: string, note: string, values: Partial<ProgressEntry> = {}): ProgressEntry {
  return {
    id,
    upgradeId: UPGRADE_ID,
    userId: 'user-1',
    date,
    completed: true,
    numericValue: null,
    unit: null,
    rating: null,
    note,
    createdAt: `${date}T20:00:00`,
    ...values,
  };
}

function aReflection(id: string, date: string, whatWorked: string): Reflection {
  return { id, upgradeId: UPGRADE_ID, userId: 'user-1', date, whatWorked, createdAt: `${date}T20:00:00` };
}

let navigate: NavigateFunction;

/** Holds on to the router's `navigate` from outside the page's route, where the notification bell sits. */
function NavigateHandle() {
  navigate = useNavigate();
  return null;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/upgrades/${UPGRADE_ID}`]}>
        <NavigateHandle />
        <Routes>
          <Route path="/upgrades/:id" element={<UpgradeDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** True when `first` precedes `second` in document order. */
function isBefore(first: HTMLElement, second: HTMLElement): boolean {
  return (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

/**
 * FR-20 and FR-24 — an upgrade's progress history and its reflections read newest first.
 *
 * The API already returns both lists in that order, so what these pin is that the page keeps it. The
 * progress list scrolls inside a fixed height, which is why the order matters: whatever is listed last
 * is out of sight. Order is read from distinctive text rather than from rendered dates, which are
 * parsed as UTC and can show the day before (#95).
 */
describe('UpgradeDetailsPage', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    getProgressByUpgrade.mockReset();
    createProgress.mockReset();
    getReflectionsByUpgrade.mockReset();
    getUpgradeById.mockResolvedValue(anUpgrade());
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
  });

  it('GivenProgressReturnedNewestFirst_WhenTheDetailsPageRenders_ThenTheNewestEntryIsListedFirst', async () => {
    getProgressByUpgrade.mockResolvedValue([
      anEntry('p-3', '2026-03-12', 'third day'),
      anEntry('p-2', '2026-03-11', 'second day'),
      anEntry('p-1', '2026-03-10', 'first day'),
    ]);

    renderPage();

    const newest = await screen.findByText('third day');
    const middle = screen.getByText('second day');
    const oldest = screen.getByText('first day');
    expect(isBefore(newest, middle)).toBe(true);
    expect(isBefore(middle, oldest)).toBe(true);
  });

  it('GivenReflectionsReturnedNewestFirst_WhenTheDetailsPageRenders_ThenTheNewestReflectionIsListedFirst', async () => {
    getReflectionsByUpgrade.mockResolvedValue([
      aReflection('r-2', '2026-03-14', 'Went in before breakfast'),
      aReflection('r-1', '2026-03-07', 'Counted to thirty'),
    ]);

    renderPage();

    const newest = await screen.findByText('Went in before breakfast');
    const oldest = screen.getByText('Counted to thirty');
    expect(isBefore(newest, oldest)).toBe(true);
  });

  /**
   * FR-18 (#116) — progress is logged against the upgrade the page is showing.
   *
   * React Router keeps the same page instance when only `:id` changes, which is what the notification
   * bell, a toast and the notifications page do. Anything the page captured from the first id would
   * outlive the move, so the test moves between two upgrades before it logs.
   */
  it('GivenTheUserMovedFromOneUpgradeToAnother_WhenTheyLogProgress_ThenItIsPostedAgainstTheUpgradeOnScreen', async () => {
    getUpgradeById.mockImplementation((id: string) =>
      Promise.resolve(id === OTHER_UPGRADE_ID ? anUpgrade(OTHER_UPGRADE_ID, 'Evening walk') : anUpgrade()),
    );
    createProgress.mockResolvedValue(anEntry('p-1', '2026-03-12', 'logged'));
    renderPage();
    await screen.findByText('Cold showers');

    act(() => navigate(`/upgrades/${OTHER_UPGRADE_ID}`));
    await screen.findByText('Evening walk');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(1));
    expect(createProgress.mock.calls[0][0]).toMatchObject({ upgradeId: OTHER_UPGRADE_ID });
  });

  // FR-20 (#117) — a history row shows only the values its entry carries. The API sends an unused field
  // as null, so these guard against a check for undefined, which lets null through.

  it('GivenAYesNoEntryAsTheApiSendsIt_WhenTheDetailsPageRenders_ThenNoRatingIsShown', async () => {
    getProgressByUpgrade.mockResolvedValue([anEntry('p-1', '2026-03-12', 'went in')]);

    renderPage();

    await screen.findByText('went in');
    expect(screen.queryByText(/⭐/)).toBeNull();
  });

  it('GivenAnEntryWithNoVerdict_WhenTheDetailsPageRenders_ThenNeitherDoneNorMissedIsShown', async () => {
    getProgressByUpgrade.mockResolvedValue([anEntry('p-1', '2026-03-12', 'went in', { completed: null })]);

    renderPage();

    await screen.findByText('went in');
    expect(screen.queryByText('Missed')).toBeNull();
    expect(screen.queryByText('Done')).toBeNull();
  });

  it('GivenARatedEntry_WhenTheDetailsPageRenders_ThenItsRatingIsShown', async () => {
    getProgressByUpgrade.mockResolvedValue([anEntry('p-1', '2026-03-12', 'went in', { rating: 4 })]);

    renderPage();

    expect(await screen.findByText('⭐ 4/5')).toBeDefined();
  });

  it('GivenANumericEntryOfZero_WhenTheDetailsPageRenders_ThenTheZeroIsShown', async () => {
    getProgressByUpgrade.mockResolvedValue([
      anEntry('p-1', '2026-03-12', 'rest day', { completed: false, numericValue: 0, unit: 'km' }),
    ]);

    renderPage();

    expect(await screen.findByText('0 km')).toBeDefined();
  });
});
