import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import UpgradeDetailsPage from './UpgradeDetailsPage';
import type { HealthUpgrade, ProgressEntry, Reflection } from '../types';

const getUpgradeById = vi.fn();
const getProgressByUpgrade = vi.fn();
const getReflectionsByUpgrade = vi.fn();

vi.mock('../api/upgrades', () => ({ getUpgradeById: (...a: unknown[]) => getUpgradeById(...a) }));
vi.mock('../api/progress', () => ({
  getProgressByUpgrade: (...a: unknown[]) => getProgressByUpgrade(...a),
  createProgress: vi.fn(),
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

function anUpgrade(): HealthUpgrade {
  return {
    id: UPGRADE_ID,
    userId: 'user-1',
    title: 'Cold showers',
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'MEDIUM',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  };
}

function anEntry(id: string, date: string, note: string): ProgressEntry {
  return { id, upgradeId: UPGRADE_ID, userId: 'user-1', date, completed: true, note, createdAt: `${date}T20:00:00` };
}

function aReflection(id: string, date: string, whatWorked: string): Reflection {
  return { id, upgradeId: UPGRADE_ID, userId: 'user-1', date, whatWorked, createdAt: `${date}T20:00:00` };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/upgrades/${UPGRADE_ID}`]}>
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
});
