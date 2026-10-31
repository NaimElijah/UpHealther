import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import DashboardPage from './DashboardPage';
import type { AreaSummary, DashboardDto, HealthUpgrade } from '../types';
import { upgradeAsSent } from '../test/wireFixtures';
import { headingOutlineProblems } from '../test/headingOutline';

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
  return upgradeAsSent({
    id,
    userId: 'user-1',
    title,
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'MEDIUM',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  });
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
    getDashboard.mockResolvedValue(
      aDashboard([
        { areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 4, activeCount: 2, completedCount: 1 },
        { areaId: 'area-2', areaName: 'Nutrition', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
      ]),
    );

    renderPage();

    const areas = await findAreaRows();
    expect(areas).toHaveLength(2);
    expect(rowFor(areas, 'Sleep').textContent).toContain('2 active · 1 completed · 4 total');
    expect(rowFor(areas, 'Nutrition').textContent).toContain('0 active · 0 completed · 0 total');
  });

  it('GivenAreasInNoParticularOrder_WhenTheDashboardRenders_ThenTheyAreListedByName', async () => {
    // The server's area query has no ORDER BY, so the same areas can arrive in a different order from
    // one load to the next. Sorting here keeps the rows from shuffling between refreshes.
    getDashboard.mockResolvedValue(
      aDashboard([
        { areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
        { areaId: 'area-2', areaName: 'Movement', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
        { areaId: 'area-3', areaName: 'Nutrition', totalUpgrades: 0, activeCount: 0, completedCount: 0 },
      ]),
    );

    renderPage();

    const names = (await findAreaRows()).map((row) => row.firstElementChild?.textContent);
    expect(names).toEqual(['Movement', 'Nutrition', 'Sleep']);
  });

  it('GivenAreasInTheSummary_WhenTheDashboardRenders_ThenTheListDeclaresItsRoleExplicitly', async () => {
    // Tailwind's preflight strips list-style, and WebKit then stops exposing a <ul> as a list, which
    // takes its aria-label with it. jsdom does not copy that behaviour, so the implicit role would pass
    // the query above either way. The explicit attribute is what VoiceOver actually relies on.
    getDashboard.mockResolvedValue(
      aDashboard([{ areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 1, activeCount: 1, completedCount: 0 }]),
    );

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
    getDashboard.mockResolvedValue(
      aDashboardWithStreaks(
        [
          anActiveUpgrade('u-1', 'Cold showers'),
          anActiveUpgrade('u-2', 'Evening walk'),
          anActiveUpgrade('u-3', 'No sugar'),
        ],
        { 'u-1': 5, 'u-2': 0, 'u-3': 0 },
      ),
    );

    renderPage();

    expect(await tileFigure('Active')).toBe('3');
    expect(await tileFigure('Streaks')).toBe('1');
  });

  it('GivenARunningStreak_WhenTheDashboardRenders_ThenItsTileNamesTheUpgrade', async () => {
    getDashboard.mockResolvedValue(
      aDashboardWithStreaks([anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')], {
        'u-1': 5,
        'u-2': 0,
      }),
    );

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
    getDashboard.mockResolvedValue(
      aDashboardWithStreaks([anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')], {
        'u-1': 0,
        'u-2': 0,
      }),
    );

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
      apiFailure(409, {
        status: 409,
        message: 'Resource was modified concurrently. Please retry.',
        traceId: 'trace-71',
      }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /^Pause / }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That change did not go through.');
    expect(alert.textContent).toContain('Resource was modified concurrently. Please retry.');
    expect(alert.textContent).toContain('trace-71');
  });

  it('GivenAChangeInFlight_WhenAnotherCardIsPressed_ThenNothingMoreIsSent', async () => {
    getDashboard.mockResolvedValue(
      aDashboardWithStreaks([anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')], {
        'u-1': 0,
        'u-2': 0,
      }),
    );
    performUpgradeAction.mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByText('Evening walk');

    fireEvent.click(screen.getAllByRole('button', { name: /^Pause / })[0]);
    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getAllByRole('button', { name: /^Pause / })[1]);

    await settled();
    expect(performUpgradeAction).toHaveBeenCalledTimes(1);
  });

  it('GivenAnUpgradeListedInTwoSections_WhenAPauseIsRefusedInOne_ThenTheRefusalIsShownOnceWithThatCard', async () => {
    // Today's Health Moves and Active Upgrades can both list one upgrade. The refusal belongs with the card
    // that was pressed, further down the page than the greeting, and only once.
    const upgrade = anActiveUpgrade('u-1', 'Cold showers');
    getDashboard.mockResolvedValue({ ...aDashboardWithStreaks([upgrade], { 'u-1': 0 }), todayUpgrades: [upgrade] });
    performUpgradeAction.mockRejectedValue(
      apiFailure(409, {
        status: 409,
        message: 'Resource was modified concurrently. Please retry.',
        traceId: 'trace-72',
      }),
    );
    renderPage();
    await screen.findByText('Active Upgrades');
    const [, inActiveSection] = screen.getAllByText('Cold showers');

    fireEvent.click(screen.getAllByRole('button', { name: /^Pause / })[1]);

    const alert = await screen.findByRole('alert');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect((inActiveSection.compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0).toBe(true);
  });
});

/** The card headed by `heading`, once the dashboard has loaded: the heading sits in the card's header row. */
async function findCard(heading: string | RegExp): Promise<HTMLElement> {
  const header = await screen.findByRole('heading', { level: 2, name: heading });
  const card = header.parentElement?.parentElement;
  if (!card) throw new Error(`No card around the heading "${String(heading)}"`);
  return card;
}

/**
 * FR-27 — the rest of what the dashboard shows: the overdue warning, today's upgrades, the recently
 * completed ones and the weekly completion rate.
 */
describe('DashboardPage sections', () => {
  beforeEach(() => {
    getDashboard.mockReset();
    performUpgradeAction.mockReset();
  });

  it('GivenTwoOverdueUpgrades_WhenTheDashboardRenders_ThenTheWarningCountsThem', async () => {
    getDashboard.mockResolvedValue({
      ...aDashboard([]),
      overdueUpgrades: [anActiveUpgrade('u-1', 'Cold showers'), anActiveUpgrade('u-2', 'Evening walk')],
    });

    renderPage();

    expect(await screen.findByText('2 overdue upgrades')).toBeDefined();
    expect(screen.getByText('Some upgrades are past their target date.')).toBeDefined();
  });

  it('GivenOneOverdueUpgrade_WhenTheDashboardRenders_ThenTheWarningIsSingular', async () => {
    getDashboard.mockResolvedValue({ ...aDashboard([]), overdueUpgrades: [anActiveUpgrade('u-1', 'Cold showers')] });

    renderPage();

    expect(await screen.findByText('1 overdue upgrade')).toBeDefined();
  });

  it('GivenNothingOverdue_WhenTheDashboardRenders_ThenNoWarningIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    await screen.findByText('Weekly Completion Rate');
    expect(screen.queryByText('Some upgrades are past their target date.')).toBeNull();
  });

  it('GivenAnUpgradeDueToday_WhenTheDashboardRenders_ThenTodaysHealthMovesListsItWithTheCheckIn', async () => {
    getDashboard.mockResolvedValue({ ...aDashboard([]), todayUpgrades: [anActiveUpgrade('u-1', 'Drink water')] });

    renderPage();

    const today = await findCard("Today's Health Moves");
    expect(within(today).getByText('Drink water')).toBeDefined();
    expect(within(today).getByRole('link', { name: 'Go to Daily Check-in' })).toBeDefined();
    expect(within(today).queryByText('All done for today!')).toBeNull();
  });

  it('GivenNothingDueToday_WhenTheDashboardRenders_ThenTodaysHealthMovesSaysAllDone', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    const today = await findCard("Today's Health Moves");
    expect(within(today).getByText('All done for today!')).toBeDefined();
  });

  it('GivenARecentlyCompletedUpgrade_WhenTheDashboardRenders_ThenRecentlyCompletedListsIt', async () => {
    getDashboard.mockResolvedValue({
      ...aDashboard([]),
      recentlyCompleted: [upgradeAsSent({ id: 'u-9', title: 'Read before bed', status: 'COMPLETED' })],
    });

    renderPage();

    const completed = await findCard(/^Recently Completed/);
    expect(within(completed).getByText('Read before bed')).toBeDefined();
  });

  it('GivenNothingRecentlyCompleted_WhenTheDashboardRenders_ThenNoRecentlyCompletedSectionIsShown', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    await screen.findByText('Weekly Completion Rate');
    expect(screen.queryByText(/^Recently Completed/)).toBeNull();
  });

  // The API sends the rate as a percentage with a fraction (DashboardDto.weeklyCompletionRate), so the page
  // rounds it to the nearest whole number rather than scaling or truncating it.
  it.each([
    [66.67, '67%'],
    [12.4, '12%'],
    [100, '100%'],
  ])('GivenAWeeklyRateOf %s_WhenTheDashboardRenders_ThenItReads %s', async (rate, shown) => {
    getDashboard.mockResolvedValue({ ...aDashboard([]), weeklyCompletionRate: rate });

    renderPage();

    expect(await tileFigure('Weekly Rate')).toBe(shown);
  });
});

/**
 * The greeting names the part of the user's own day: morning until noon, afternoon until six, evening
 * after. The times are built on the local clock, which the suite pins to Los Angeles, so each case sits
 * on one side of a cut-off on every machine.
 */
describe('DashboardPage greeting', () => {
  beforeEach(() => {
    getDashboard.mockReset();
    getDashboard.mockResolvedValue(aDashboard([]));
    vi.useFakeTimers({ toFake: ['Date'] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each([
    ['1159', 'Morning', 11, 59],
    ['1200', 'Afternoon', 12, 0],
    ['1759', 'Afternoon', 17, 59],
    ['1800', 'Evening', 18, 0],
  ] as const)(
    'GivenTheLocalTime%s_WhenTheDashboardIsShown_ThenTheGreetingSaysGood%s',
    async (_time, partOfDay, hour, minute) => {
      vi.setSystemTime(new Date(2026, 2, 12, hour, minute));

      renderPage();

      expect((await screen.findByRole('heading', { level: 1 })).textContent).toBe(
        `Good ${partOfDay.toLowerCase()}, Ada 👋`,
      );
    },
  );
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('DashboardPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    getDashboard.mockResolvedValue(aDashboard([]));

    renderPage();

    expect(document.title).toBe('Dashboard – UpHealther');
  });
});

/**
 * Completing or pausing an upgrade takes its card off the dashboard, and with it the button that had
 * focus, so focus fell to `<body>`. It goes to the page's heading instead, and the pressed button keeps
 * it while the change is in flight (WCAG 2.4.3, finding X9.4).
 */
describe('DashboardPage focus after a transition', () => {
  beforeEach(() => {
    getDashboard.mockReset();
    performUpgradeAction.mockReset();
  });

  it.each(['Complete', 'Pause'])(
    'GivenFocusOn%sOfAnActiveUpgrade_WhenTheChangeLands_ThenFocusMovesToThePageHeading',
    async (action) => {
      const walk = anActiveUpgrade('upgrade-1', 'Evening walk');
      getDashboard
        .mockResolvedValueOnce({ ...aDashboard([]), activeUpgrades: [walk] })
        .mockResolvedValue(aDashboard([]));
      performUpgradeAction.mockResolvedValue(walk);
      renderPage();
      const button = await screen.findByRole('button', { name: new RegExp(`^${action} `) });
      button.focus();

      fireEvent.click(button);

      await waitFor(() => expect(screen.queryByRole('button', { name: new RegExp(`^${action} `) })).toBeNull());
      await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })));
    },
  );

  // Review finding 2 on #407: the hand-on moved focus without asking where it was, so a user who had
  // moved on while the change was in flight was thrown back to the top of the page.
  it('GivenFocusMovedWhileInFlight_WhenTheChangeLands_ThenFocusStaysWhereTheUserPutIt', async () => {
    const first = anActiveUpgrade('upgrade-1', 'First walk');
    const second = anActiveUpgrade('upgrade-2', 'Second walk');
    getDashboard
      .mockResolvedValueOnce({ ...aDashboard([]), activeUpgrades: [first, second] })
      .mockResolvedValue({ ...aDashboard([]), activeUpgrades: [second] });
    let land: (done: HealthUpgrade) => void = () => {};
    performUpgradeAction.mockReturnValue(new Promise<HealthUpgrade>((resolve) => (land = resolve)));
    renderPage();
    const complete = await screen.findByRole('button', { name: 'Complete First walk' });
    complete.focus();
    fireEvent.click(complete);
    await settled();

    const secondWalk = screen.getByRole('link', { name: 'Second walk' });
    secondWalk.focus();
    await act(async () => land(first));

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Complete First walk' })).toBeNull());
    await settled();
    expect(document.activeElement).toBe(secondWalk);
  });

  it('GivenAChangeInFlight_WhenItsButtonHasFocus_ThenTheButtonKeepsItAndSaysItIsBusy', async () => {
    getDashboard.mockResolvedValue({
      ...aDashboard([]),
      activeUpgrades: [anActiveUpgrade('upgrade-1', 'Evening walk')],
    });
    performUpgradeAction.mockReturnValue(new Promise(() => {}));
    renderPage();
    const complete = await screen.findByRole('button', { name: /^Complete / });
    complete.focus();

    fireEvent.click(complete);
    await settled();

    expect(document.activeElement).toBe(complete);
    expect(complete.hasAttribute('disabled')).toBe(false);
    expect(complete.getAttribute('aria-busy')).toBe('true');
  });
});

/**
 * The page's headings form an outline: one `h1`, and no level skipped below it, a failed load included.
 * Section titles were plain text, cards were `h3`s straight under the `h1`, and a page that failed to load
 * had no `h1` at all (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('DashboardPage headings', () => {
  beforeEach(() => {
    getDashboard.mockReset();
  });

  it('GivenThePageHasLoaded_WhenItRenders_ThenItsHeadingsFormAnOutlineWithoutGaps', async () => {
    getDashboard.mockResolvedValue({
      ...aDashboardWithStreaks([anActiveUpgrade('upgrade-1', 'Evening walk')], { 'upgrade-1': 3 }),
      todayUpgrades: [anActiveUpgrade('upgrade-1', 'Evening walk')],
      areaSummary: [{ areaId: 'area-1', areaName: 'Sleep', totalUpgrades: 1, activeCount: 1, completedCount: 0 }],
    });

    renderPage();
    await screen.findAllByText('Evening walk');

    expect(headingOutlineProblems()).toEqual([]);
  });

  it('GivenTheLoadFails_WhenThePageRenders_ThenTheFailureSitsUnderThePagesHeading', async () => {
    getDashboard.mockRejectedValue(new Error('down'));

    renderPage();
    await screen.findByRole('alert');

    expect(screen.getByRole('heading', { level: 1 })).toBeDefined();
    expect(headingOutlineProblems()).toEqual([]);
  });
});

/**
 * The dashboard's decorative emoji are not part of what it says: the greeting's wave, the overdue
 * warning's sign and the streaks card's flame were read aloud with the text (WCAG 1.1.1, finding X9.24).
 */
describe('DashboardPage emoji', () => {
  beforeEach(() => {
    getDashboard.mockReset();
  });

  it('GivenStreaksAndAnOverdueUpgrade_WhenTheDashboardRenders_ThenTheHeadingsAndTheWarningAreNamedWithoutEmoji', async () => {
    const walk = anActiveUpgrade('upgrade-1', 'Evening walk');
    getDashboard.mockResolvedValue({ ...aDashboardWithStreaks([walk], { 'upgrade-1': 3 }), overdueUpgrades: [walk] });
    renderPage();

    expect(await screen.findByRole('heading', { level: 1, name: /^Good \w+, Ada$/ })).toBeDefined();
    expect(screen.getByRole('heading', { level: 2, name: 'Current Streaks' })).toBeDefined();
    expect(screen.getByText('⚠️').getAttribute('aria-hidden')).toBe('true');
  });
});

/**
 * What only takes the user to another page is a link, styled as a button: announced as a link, and
 * openable in a new tab. These were buttons that called `navigate` (WCAG 4.1.2, finding X9.25).
 */
describe('DashboardPage links', () => {
  beforeEach(() => {
    getDashboard.mockReset();
  });

  it('GivenUpgradesDueToday_WhenTheDashboardRenders_ThenItsWaysToOtherPagesAreLinks', async () => {
    const walk = anActiveUpgrade('upgrade-1', 'Evening walk');
    getDashboard.mockResolvedValue({ ...aDashboard([]), activeUpgrades: [walk], todayUpgrades: [walk] });
    renderPage();
    await screen.findAllByText('Evening walk');

    expect(screen.getByRole('link', { name: '+ Add Upgrade' }).getAttribute('href')).toBe('/upgrades/backlog');
    expect(screen.getByRole('link', { name: 'Go to Daily Check-in' }).getAttribute('href')).toBe('/daily-checkin');
  });

  it('GivenNoActiveUpgrades_WhenTheDashboardRenders_ThenViewPlannedIsALink', async () => {
    getDashboard.mockResolvedValue(aDashboard([]));
    renderPage();
    await screen.findByText('No active upgrades');

    expect(screen.getByRole('link', { name: 'View Planned' }).getAttribute('href')).toBe('/upgrades/planned');
  });
});
