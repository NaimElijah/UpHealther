import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ProgressHistoryPage from './ProgressHistoryPage';
import type { HealthUpgrade, ProgressEntry } from '../types';
import { upgradeAsSent } from '../test/wireFixtures';
import { headingOutlineProblems } from '../test/headingOutline';

const getWeekProgress = vi.fn();
const getUpgrades = vi.fn();

vi.mock('../api/progress', () => ({ getWeekProgress: (...a: unknown[]) => getWeekProgress(...a) }));
vi.mock('../api/upgrades', () => ({ getUpgrades: (...a: unknown[]) => getUpgrades(...a) }));

const UPGRADE_ID = 'upgrade-1';

function anUpgrade(): HealthUpgrade {
  return upgradeAsSent({
    id: UPGRADE_ID,
    userId: 'user-1',
    title: 'Morning stretch',
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'EASY',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  });
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

  // Review finding 22 on #407: "Done" and "Missed" were read after "check mark" and "ballot X".
  it.each([
    ['Done', true, '✓'],
    ['Missed', false, '✗'],
  ])('GivenAnEntryMarked%s_WhenTheWeekIsListed_ThenItsTickOrCrossIsHidden', async (_, completed, symbol) => {
    getWeekProgress.mockResolvedValue([anEntry({ completed })]);

    renderPage();

    expect((await screen.findByText(symbol)).getAttribute('aria-hidden')).toBe('true');
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

/** The page lists the week newest first, whatever order the API sends the entries in. */
describe('ProgressHistoryPage order', () => {
  beforeEach(() => {
    getWeekProgress.mockReset();
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([anUpgrade()]);
  });

  it('GivenEntriesInNoParticularOrder_WhenTheWeekIsListed_ThenTheNewestIsFirst', async () => {
    getWeekProgress.mockResolvedValue([
      anEntry({ id: 'p-1', date: '2026-03-10', note: 'on the tenth' }),
      anEntry({ id: 'p-2', date: '2026-03-12', note: 'on the twelfth' }),
      anEntry({ id: 'p-3', date: '2026-03-11', note: 'on the eleventh' }),
    ]);

    renderPage();

    await screen.findByText('on the tenth');
    expect(screen.getAllByText(/^on the /).map((note) => note.textContent)).toEqual([
      'on the twelfth',
      'on the eleventh',
      'on the tenth',
    ]);
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('ProgressHistoryPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    getWeekProgress.mockResolvedValue([]);
    getUpgrades.mockResolvedValue([]);

    renderPage();

    expect(document.title).toBe('Progress History – UpHealther');
  });
});

/**
 * The page's headings form an outline: one `h1`, and no level skipped below it, a failed load included.
 * Section titles were plain text, cards were `h3`s straight under the `h1`, and a page that failed to load
 * had no `h1` at all (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('ProgressHistoryPage headings', () => {
  beforeEach(() => {
    getWeekProgress.mockReset();
    getUpgrades.mockReset();
  });

  it('GivenTheLoadFails_WhenThePageRenders_ThenTheFailureSitsUnderThePagesHeading', async () => {
    getWeekProgress.mockRejectedValue(new Error('down'));
    getUpgrades.mockResolvedValue([]);

    renderPage();
    await screen.findByRole('alert');

    expect(screen.getByRole('heading', { level: 1 })).toBeDefined();
    expect(headingOutlineProblems()).toEqual([]);
  });
});

/**
 * The week's entries are a list, and a rating is read as a score on its scale. The entries were a stack of
 * divs, and a rating was a row of stars read aloud one by one with no scale (WCAG 1.1.1, 1.3.1,
 * finding X9.23).
 */
describe('ProgressHistoryPage entries', () => {
  beforeEach(() => {
    getWeekProgress.mockReset();
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([anUpgrade()]);
  });

  it('GivenTwoEntries_WhenTheWeekIsListed_ThenTheyAreItemsOfAList', async () => {
    getWeekProgress.mockResolvedValue([anEntry({ id: 'e1', note: 'first' }), anEntry({ id: 'e2', note: 'second' })]);
    renderPage();

    await screen.findByText('first');
    expect(within(screen.getByRole('list')).getAllByRole('listitem')).toHaveLength(2);
  });

  it('GivenARatedEntry_WhenTheWeekIsListed_ThenItsRatingIsReadAsAScoreOutOfFiveAndTheStarsAreHidden', async () => {
    getWeekProgress.mockResolvedValue([anEntry({ rating: 4 })]);
    renderPage();

    expect(await screen.findByText('Rating 4 of 5')).toBeDefined();
    expect(screen.getByText('⭐⭐⭐⭐').getAttribute('aria-hidden')).toBe('true');
  });
});
