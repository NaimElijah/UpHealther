import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import PlannedUpgradesPage from './PlannedUpgradesPage';
import type { HealthUpgrade } from '../types';
import { upgradeAsSent } from '../test/wireFixtures';
import { headingOutlineProblems } from '../test/headingOutline';

const getUpgrades = vi.fn();
const performUpgradeAction = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
}));

function aPlannedHardUpgrade(): HealthUpgrade {
  return upgradeAsSent({
    id: 'upgrade-1',
    userId: 'user-1',
    title: 'Run a half marathon',
    type: 'GOAL',
    status: 'PLANNED',
    difficulty: 'HARD',
    version: 0,
    createdAt: '2026-03-01T09:00:00',
  });
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
        <PlannedUpgradesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** NFR-30 (#96) — an activation that fails says so, with the trace id that finds it. */
describe('PlannedUpgradesPage', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    performUpgradeAction.mockReset();
    getUpgrades.mockResolvedValue([aPlannedHardUpgrade()]);
  });

  it('GivenThreeHardUpgradesAlreadyRunning_WhenAFourthIsActivated_ThenTheCardSaysWhyWithTheReference', async () => {
    // The likeliest refusal on this page: the HARD-slot limit is the one rule a user cannot see coming.
    performUpgradeAction.mockRejectedValue(
      apiFailure(422, {
        status: 422,
        message: 'Cannot run more than 3 HARD upgrades simultaneously',
        traceId: 'trace-61',
      }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /^Activate / }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That change did not go through.');
    expect(alert.textContent).toContain('Cannot run more than 3 HARD upgrades simultaneously');
    expect(alert.textContent).toContain('trace-61');
    // With the card, below its title, rather than above the list where a long timeline hides it.
    expect(
      (screen.getByText('Run a half marathon').compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0,
    ).toBe(true);
  });

  it('GivenAnActivationInFlight_WhenActivateIsPressedAgain_ThenNothingMoreIsSent', async () => {
    performUpgradeAction.mockReturnValue(new Promise(() => {}));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /^Activate / }));
    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: /^Activate / }));

    await settled();
    expect(performUpgradeAction).toHaveBeenCalledTimes(1);
  });
});

/**
 * The planned list reads as a timeline: soonest start first, and an upgrade with no start date last
 * rather than first. The API returns the list unordered, so this order is the page's alone.
 */
describe('PlannedUpgradesPage order', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    performUpgradeAction.mockReset();
  });

  it('GivenPlannedUpgradesInNoParticularOrder_WhenThePageRenders_ThenTheSoonestIsFirstAndTheUnscheduledLast', async () => {
    getUpgrades.mockResolvedValue([
      upgradeAsSent({ id: 'u-1', title: 'Starts on the tenth', status: 'PLANNED', plannedStartDate: '2026-04-10' }),
      upgradeAsSent({ id: 'u-2', title: 'Not scheduled', status: 'PLANNED', plannedStartDate: null }),
      upgradeAsSent({ id: 'u-3', title: 'Starts on the second', status: 'PLANNED', plannedStartDate: '2026-04-02' }),
    ]);

    renderPage();

    await screen.findByText('Not scheduled');
    expect(screen.getAllByRole('heading', { level: 3 }).map((title) => title.textContent)).toEqual([
      'Starts on the second',
      'Starts on the tenth',
      'Not scheduled',
    ]);
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('PlannedUpgradesPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    getUpgrades.mockResolvedValue([]);

    renderPage();

    expect(document.title).toBe('Planned Upgrades – UpHealther');
  });
});

/**
 * The page's headings form an outline: one `h1`, and no level skipped below it, a failed load included.
 * Section titles were plain text, cards were `h3`s straight under the `h1`, and a page that failed to load
 * had no `h1` at all (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('PlannedUpgradesPage headings', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
  });

  it('GivenThePageHasLoaded_WhenItRenders_ThenItsHeadingsFormAnOutlineWithoutGaps', async () => {
    getUpgrades.mockResolvedValue([aPlannedHardUpgrade()]);

    renderPage();
    await screen.findByText('Run a half marathon');

    expect(headingOutlineProblems()).toEqual([]);
  });

  it('GivenTheLoadFails_WhenThePageRenders_ThenTheFailureSitsUnderThePagesHeading', async () => {
    getUpgrades.mockRejectedValue(new Error('down'));

    renderPage();
    await screen.findByRole('alert');

    expect(screen.getByRole('heading', { level: 1 })).toBeDefined();
    expect(headingOutlineProblems()).toEqual([]);
  });
});

/**
 * What only takes the user to another page is a link, styled as a button: announced as a link, and
 * openable in a new tab. These were buttons that called `navigate` (WCAG 4.1.2, finding X9.25).
 */
describe('PlannedUpgradesPage links', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([]);
  });

  it('GivenNothingPlanned_WhenThePageRenders_ThenItsWaysToTheBacklogAreLinks', async () => {
    renderPage();
    await screen.findByText('No planned upgrades');

    expect(screen.getByRole('link', { name: 'Browse Backlog' }).getAttribute('href')).toBe('/upgrades/backlog');
    expect(screen.getByRole('link', { name: 'Go to Backlog' }).getAttribute('href')).toBe('/upgrades/backlog');
  });
});
