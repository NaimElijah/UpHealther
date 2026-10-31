import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import DailyCheckinPage from './DailyCheckinPage';
import { createQueryClient } from '../lib/queryClient';
import type { HealthUpgrade } from '../types';
import { trackingConfigAsSent, upgradeAsSent } from '../test/wireFixtures';
import { headingOutlineProblems } from '../test/headingOutline';

const getUpgrades = vi.fn();
const createProgress = vi.fn();

vi.mock('../api/upgrades', () => ({ getUpgrades: (...a: unknown[]) => getUpgrades(...a) }));
vi.mock('../api/progress', () => ({ createProgress: (...a: unknown[]) => createProgress(...a) }));

function anUpgrade(overrides: Partial<HealthUpgrade> = {}): HealthUpgrade {
  return upgradeAsSent({
    title: 'Cold showers',
    createdAt: '2026-03-15T09:00:00',
    updatedAt: '2026-03-15T09:00:00',
    ...overrides,
  });
}

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

function renderPage(queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <DailyCheckinPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * FR-19 — a user can log progress for every active upgrade in one pass.
 *
 * The page renders a different control per tracking type, which is where it can silently go wrong: an
 * upgrade configured NUMERIC but rendered with a checkbox produces an entry the server scores against a
 * target that was never compared.
 *
 * <strong>One assertion below records behaviour that is still a product question.</strong>
 * `handleSubmit` maps over every active upgrade and posts an entry for each, touched or not. Because at
 * most one entry may exist per upgrade per day (BR-6), an untouched upgrade is therefore logged as a
 * failure that cannot be corrected later the same day. Asserted as it behaves; whether an untouched
 * upgrade should be sent at all is #55's decision rather than a test's to make.
 */
describe('DailyCheckinPage', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    createProgress.mockReset();
    createProgress.mockResolvedValue({});
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('GivenNoActiveUpgrades_WhenTheCheckinIsOpened_ThenItSaysThereIsNothingToTrack', async () => {
    getUpgrades.mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(screen.getByText('No active upgrades')).toBeDefined());
  });

  it('GivenOnlyActiveUpgradesAreWanted_WhenTheCheckinIsOpened_ThenThatIsWhatIsAskedFor', async () => {
    // A check-in listing planned or completed upgrades would invite logging progress against something
    // that is not running.
    getUpgrades.mockResolvedValue([]);

    renderPage();

    await waitFor(() => expect(getUpgrades).toHaveBeenCalledWith('ACTIVE'));
  });

  it('GivenSeveralActiveUpgrades_WhenTheFormIsSubmittedOnce_ThenEveryOneOfThemIsLogged', async () => {
    // FR-19 itself: one pass, not one save per upgrade.
    getUpgrades.mockResolvedValue([
      anUpgrade({ id: 'a', title: 'Cold showers' }),
      anUpgrade({ id: 'b', title: 'Walk 10k' }),
      anUpgrade({ id: 'c', title: 'Read' }),
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Walk 10k')).toBeDefined());

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(3));
    expect(createProgress.mock.calls.map(([entry]) => entry.upgradeId).sort()).toEqual(['a', 'b', 'c']);
  });

  it('GivenAnUpgradeNobodyFilledIn_WhenTheFormIsSubmitted_ThenAnEntryIsStillPostedForIt', async () => {
    // #55 decides whether this is right — see the note on this describe block. Recorded so the
    // behaviour is visible rather than discovered again from a support question.
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'untouched' })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Cold showers')).toBeDefined());

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(1));
    expect(createProgress.mock.calls[0][0]).toMatchObject({ upgradeId: 'untouched' });
  });

  it('GivenABooleanUpgrade_WhenItIsTicked_ThenTheEntryCarriesTheCompletionFlag', async () => {
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Completed today')).toBeDefined());

    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0]).toMatchObject({ upgradeId: 'a', completed: true });
  });

  it('GivenANumericUpgrade_WhenAValueIsEntered_ThenTheEntryCarriesItAsANumber', async () => {
    getUpgrades.mockResolvedValue([
      anUpgrade({
        id: 'a',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'a', trackingType: 'NUMERIC', targetUnit: 'steps' }),
      }),
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByText('steps')).toBeDefined());

    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '12000' } });
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0]).toMatchObject({ numericValue: 12000 });
  });

  it('GivenANumericFieldThatIsClearedAgain_WhenTheFormIsSubmitted_ThenNoValueIsSentRatherThanNaN', async () => {
    // parseFloat('') is NaN, which serialises to null and would be read as "logged nothing" instead of
    // "did not log".
    getUpgrades.mockResolvedValue([
      anUpgrade({
        id: 'a',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'a', trackingType: 'NUMERIC', targetUnit: 'steps' }),
      }),
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByText('steps')).toBeDefined());

    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '5' } });
    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '' } });
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0].numericValue).toBeUndefined();
  });

  it('GivenARatingUpgrade_WhenAScoreIsChosen_ThenTheEntryCarriesIt', async () => {
    getUpgrades.mockResolvedValue([
      anUpgrade({
        id: 'a',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'a', trackingType: 'RATING' }),
      }),
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Rate 4 of 5' })).toBeDefined());

    fireEvent.click(screen.getByRole('radio', { name: 'Rate 4 of 5' }));
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0]).toMatchObject({ rating: 4 });
  });

  it('GivenLateEveningWestOfUtc_WhenTheCheckinIsSubmitted_ThenTheEntryIsDatedTheLocalDay', async () => {
    // FR-19 (#95). The suite runs in Los Angeles, where 22:00 on 11 March is already 12 March in UTC.
    // Dated in UTC, the entry would take tomorrow's one slot (BR-6) and leave today unlogged.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Cold showers')).toBeDefined());

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0]).toMatchObject({ date: '2026-03-11' });
  });

  it('GivenTheCheckinIsSubmitted_WhenEveryEntrySucceeds_ThenTheFormIsReplacedByAConfirmation', async () => {
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Cold showers')).toBeDefined());

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(screen.getByText('Check-in Complete!')).toBeDefined());
    expect(screen.queryByRole('button', { name: /submit check-in/i })).toBeNull();
  });

  // NFR-30 (#96) — a check-in that fails says so, with the trace id that finds it. Before, a failed load
  // rendered as "No active upgrades" and a failed submit was an unhandled rejection that showed nothing.

  it('GivenTheActiveUpgradesCannotBeLoaded_WhenTheCheckinIsOpened_ThenItSaysSoWithTheReferenceRatherThanNothingToTrack', async () => {
    getUpgrades.mockRejectedValue(
      apiFailure(500, { status: 500, message: 'Internal server error', traceId: 'trace-91' }),
    );

    renderPage();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Could not load your active upgrades.');
    expect(alert.textContent).toContain('trace-91');
    expect(screen.queryByText('No active upgrades')).toBeNull();
  });

  it('GivenTheApiRefusesAnEntry_WhenTheCheckinIsSubmitted_ThenThePageSaysSoWithTheReferenceAndKeepsTheForm', async () => {
    // A 409 is what BR-6 answers when the day was already logged, for example on the details page.
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' })]);
    createProgress.mockRejectedValue(
      apiFailure(409, { status: 409, message: 'Progress already recorded for date: 2026-03-15', traceId: 'trace-92' }),
    );
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Your check-in was not fully saved.');
    expect(alert.textContent).toContain('Progress already recorded for date: 2026-03-15');
    expect(alert.textContent).toContain('trace-92');
    expect(screen.getByRole('button', { name: /submit check-in/i })).toBeDefined();
    expect(screen.queryByText('Check-in Complete!')).toBeNull();
  });

  it('GivenOneEntryOfSeveralIsRefused_WhenTheCheckinIsSubmitted_ThenAnotherViewOfProgressIsReadAgain', async () => {
    // The entries are posted together, so the others may already be stored when one is refused. The
    // progress cache is shared with every other page and kept for thirty seconds, so it has to be
    // refreshed on a failure too, or those pages would show the day as not logged.
    const readProgressOfA = vi.fn(() => Promise.resolve([]));
    /** Another page's view of upgrade a's progress, read under the key the details page uses. */
    function ProgressOfA() {
      useQuery({ queryKey: ['progress', 'a'], queryFn: readProgressOfA });
      return null;
    }
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' }), anUpgrade({ id: 'b', title: 'Evening walk' })]);
    createProgress
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(
        apiFailure(409, { status: 409, message: 'Progress already recorded for date: 2026-03-15' }),
      );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter>
          <ProgressOfA />
          <DailyCheckinPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText('Evening walk');
    await waitFor(() => expect(readProgressOfA).toHaveBeenCalledTimes(1));

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await screen.findByRole('alert');
    await waitFor(() => expect(readProgressOfA).toHaveBeenCalledTimes(2));
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('DailyCheckinPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    getUpgrades.mockResolvedValue([]);

    renderPage();

    expect(document.title).toBe('Daily Check-in – UpHealther');
  });
});

/**
 * A submitted check-in replaces the form with its completion view, and the submit button that had focus
 * with it, so focus fell to `<body>`. The view's heading is now the page's `h1`, and takes focus
 * (WCAG 2.4.3, finding X9.4).
 */
describe('DailyCheckinPage completion', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    createProgress.mockReset();
    createProgress.mockResolvedValue({});
  });

  it('GivenAFilledCheckin_WhenItIsSubmitted_ThenTheCompletionHeadingHasFocus', async () => {
    getUpgrades.mockResolvedValue([anUpgrade()]);
    renderPage();
    const submit = await screen.findByRole('button', { name: 'Submit Check-in' });
    submit.focus();

    fireEvent.click(submit);

    const heading = await screen.findByRole('heading', { level: 1, name: 'Check-in Complete!' });
    await waitFor(() => expect(document.activeElement).toBe(heading));
  });
});

/**
 * Every input on the check-in is named, inside a group named by its upgrade, and the unit is read with
 * the amount. The amount, the note and the optional note had only a placeholder, which is gone once typed
 * into, and nothing said which upgrade a field belonged to (WCAG 1.3.1, 3.3.2, 4.1.2, finding X9.6).
 */
describe('DailyCheckinPage field names', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([
      anUpgrade({ id: 'a', title: 'Evening walk' }),
      anUpgrade({
        id: 'b',
        title: 'Drink water',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'b', trackingType: 'NUMERIC', targetUnit: 'glasses' }),
      }),
      anUpgrade({
        id: 'd',
        title: 'Journal',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'd', trackingType: 'TEXT' }),
      }),
    ]);
  });

  it('GivenAYesOrNoUpgrade_WhenTheCheckinRenders_ThenItsFieldsAreNamedInsideItsGroup', async () => {
    renderPage();

    const group = within(await screen.findByRole('group', { name: 'Evening walk' }));
    expect(group.getByRole('checkbox', { name: 'Completed today' })).toBeDefined();
    expect(group.getByRole('textbox', { name: 'Optional note' })).toBeDefined();
  });

  it('GivenANumericUpgrade_WhenTheCheckinRenders_ThenItsAmountIsNamedAndDescribedByItsUnit', async () => {
    renderPage();

    const group = within(await screen.findByRole('group', { name: 'Drink water' }));
    const amount = group.getByRole('spinbutton', { name: 'Amount' });
    expect(document.getElementById(amount.getAttribute('aria-describedby') ?? '')?.textContent).toBe('glasses');
    expect(group.getByRole('textbox', { name: 'Optional note' })).toBeDefined();
  });

  it('GivenATextUpgrade_WhenTheCheckinRenders_ThenItsNoteIsNamed', async () => {
    renderPage();

    const group = within(await screen.findByRole('group', { name: 'Journal' }));
    expect(group.getByRole('textbox', { name: 'Note' })).toBeDefined();
  });
});

/**
 * A rating is a group of five radios, each named for its place on the scale, so a screen reader says
 * which score is chosen and out of how many. They were five buttons whose choice was a change of colour
 * (WCAG 4.1.2, 1.4.1, finding X9.7).
 */
describe('DailyCheckinPage rating', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    getUpgrades.mockResolvedValue([
      anUpgrade({
        id: 'c',
        title: 'Mood',
        trackingConfig: trackingConfigAsSent({ upgradeId: 'c', trackingType: 'RATING' }),
      }),
    ]);
  });

  it('GivenARatingUpgrade_WhenTheCheckinRenders_ThenItsRatingIsANamedGroupOfFiveRadios', async () => {
    renderPage();

    const rating = within(await screen.findByRole('group', { name: 'Rating' }));
    expect(rating.getAllByRole('radio')).toHaveLength(5);
    for (const n of [1, 2, 3, 4, 5]) expect(rating.getByRole('radio', { name: `Rate ${n} of 5` })).toBeDefined();
  });

  it('GivenARatingUpgrade_WhenAScoreIsChosen_ThenThatRadioIsCheckedAndNoOther', async () => {
    renderPage();
    const rating = within(await screen.findByRole('group', { name: 'Rating' }));

    fireEvent.click(rating.getByRole('radio', { name: 'Rate 3 of 5' }));

    expect((rating.getByRole('radio', { name: 'Rate 3 of 5' }) as HTMLInputElement).checked).toBe(true);
    expect(rating.getAllByRole('radio').filter((r) => (r as HTMLInputElement).checked)).toHaveLength(1);
  });
});

/**
 * The page's headings form an outline: one `h1`, and no level skipped below it, a failed load included.
 * Section titles were plain text, cards were `h3`s straight under the `h1`, and a page that failed to load
 * had no `h1` at all (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('DailyCheckinPage headings', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
  });

  it('GivenThePageHasLoaded_WhenItRenders_ThenItsHeadingsFormAnOutlineWithoutGaps', async () => {
    getUpgrades.mockResolvedValue([anUpgrade()]);

    renderPage();
    await screen.findByText('Cold showers');

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
describe('DailyCheckinPage links', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    createProgress.mockReset();
    createProgress.mockResolvedValue({});
  });

  it('GivenNoActiveUpgrades_WhenTheCheckinOpens_ThenViewPlannedIsALink', async () => {
    getUpgrades.mockResolvedValue([]);
    renderPage();
    await screen.findByText('No active upgrades');

    expect(screen.getByRole('link', { name: 'View Planned' }).getAttribute('href')).toBe('/upgrades/planned');
  });

  it('GivenASubmittedCheckin_WhenItsCompletionShows_ThenBackToDashboardIsALink', async () => {
    getUpgrades.mockResolvedValue([anUpgrade()]);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Submit Check-in' }));
    await screen.findByText('Check-in Complete!');

    expect(screen.getByRole('link', { name: 'Back to Dashboard' }).getAttribute('href')).toBe('/dashboard');
  });
});
