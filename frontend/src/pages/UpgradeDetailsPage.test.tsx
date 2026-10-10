import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from 'react-router-dom';
import UpgradeDetailsPage from './UpgradeDetailsPage';
import type { HealthUpgrade, ProgressEntry, Reflection } from '../types';

const getUpgradeById = vi.fn();
const getProgressByUpgrade = vi.fn();
const createProgress = vi.fn();
const getReflectionsByUpgrade = vi.fn();
const createReflection = vi.fn();
const getReminders = vi.fn();
const createReminder = vi.fn();
const deleteReminder = vi.fn();
const saveTrackingConfig = vi.fn();

vi.mock('../api/upgrades', () => ({ getUpgradeById: (...a: unknown[]) => getUpgradeById(...a) }));
vi.mock('../api/progress', () => ({
  getProgressByUpgrade: (...a: unknown[]) => getProgressByUpgrade(...a),
  createProgress: (...a: unknown[]) => createProgress(...a),
  getStreak: () => Promise.resolve({ current: 0, longest: 0 }),
}));
vi.mock('../api/reflections', () => ({
  getReflectionsByUpgrade: (...a: unknown[]) => getReflectionsByUpgrade(...a),
  createReflection: (...a: unknown[]) => createReflection(...a),
}));
vi.mock('../api/reminders', () => ({
  getReminders: (...a: unknown[]) => getReminders(...a),
  createReminder: (...a: unknown[]) => createReminder(...a),
  deleteReminder: (...a: unknown[]) => deleteReminder(...a),
}));
vi.mock('../api/trackingConfig', () => ({ saveTrackingConfig: (...a: unknown[]) => saveTrackingConfig(...a) }));

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

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

/** BR-6's answer to a second entry for one day: a 409 with no field to blame, so it carries its reference. */
const DAY_ALREADY_LOGGED = apiFailure(409, {
  status: 409,
  message: 'Progress already recorded for date: 2026-03-12',
  traceId: 'trace-409',
});
const DAY_ALREADY_LOGGED_MESSAGE = 'Progress already recorded for date: 2026-03-12 (reference trace-409)';

/** A rating outside 1-5. A field refusal names its field instead of a reference, so none is shown. */
const RATING_OUT_OF_RANGE = apiFailure(400, {
  status: 400,
  message: 'Validation failed',
  fieldErrors: { benefitRating: 'must be less than or equal to 5' },
  traceId: 'trace-400',
});
const RATING_OUT_OF_RANGE_MESSAGE = 'BenefitRating: must be less than or equal to 5.';

/** BR-16's answer to a tracking unit past its column's bound. */
const OVERLONG_UNIT = apiFailure(400, {
  status: 400,
  message: 'Validation failed',
  fieldErrors: { targetUnit: 'size must be between 0 and 100' },
});
const OVERLONG_UNIT_MESSAGE = 'TargetUnit: size must be between 0 and 100.';

/** The API's answer to a reminder sent with its time cleared: `reminderTime` is required. */
const NO_REMINDER_TIME = apiFailure(400, {
  status: 400,
  message: 'Validation failed',
  fieldErrors: { reminderTime: 'must not be null' },
});

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

/**
 * Matches the element reading exactly "`label` `date`", where the label is its own `<span>` and the
 * date is a bare text node beside it — so no single text node holds both, and a plain string won't do.
 */
function labelled(label: string, date: Date) {
  const expected = `${label} ${date.toLocaleDateString()}`;
  return (_: string, element: Element | null) => element?.textContent === expected;
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
 * is out of sight. Order is read from distinctive text rather than from rendered dates, so a test about
 * order does not also depend on how a date is formatted.
 */
describe('UpgradeDetailsPage', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    getProgressByUpgrade.mockReset();
    createProgress.mockReset();
    getReflectionsByUpgrade.mockReset();
    createReflection.mockReset();
    getReminders.mockReset();
    createReminder.mockReset();
    deleteReminder.mockReset();
    saveTrackingConfig.mockReset();
    getUpgradeById.mockResolvedValue(anUpgrade());
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
    getReminders.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
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

  it('GivenAHalfWrittenEntryOnOneUpgrade_WhenTheUserMovesToAnother_ThenNoneOfItCarriesOver', async () => {
    getUpgradeById.mockImplementation((id: string) =>
      Promise.resolve(id === OTHER_UPGRADE_ID ? anUpgrade(OTHER_UPGRADE_ID, 'Evening walk') : anUpgrade()),
    );
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'meant for cold showers' } });

    act(() => navigate(`/upgrades/${OTHER_UPGRADE_ID}`));
    await screen.findByText('Evening walk');

    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('');
  });

  it('GivenASaveStillInFlightForOneUpgrade_WhenTheUserMovesToAnother_ThenItLeavesTheOtherUpgradesDialogAlone', async () => {
    getUpgradeById.mockImplementation((id: string) =>
      Promise.resolve(id === OTHER_UPGRADE_ID ? anUpgrade(OTHER_UPGRADE_ID, 'Evening walk') : anUpgrade()),
    );
    let answerTheSave: (entry: ProgressEntry) => void = () => {};
    createProgress.mockReturnValue(new Promise<ProgressEntry>((resolve) => { answerTheSave = resolve; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(1));

    act(() => navigate(`/upgrades/${OTHER_UPGRADE_ID}`));
    await screen.findByText('Evening walk');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    await act(async () => { answerTheSave(anEntry('p-1', '2026-03-12', 'logged')); });

    expect(screen.getByRole('dialog', { name: 'Log Progress' })).toBeDefined();
  });

  // FR-18 and FR-23 (#119) — each new entry starts from fresh defaults. The page can stay open across
  // midnight and is where a second entry in one visit is made, so neither yesterday's date nor the last
  // entry's values may carry into the next one.

  it('GivenThePageWasOpenedYesterday_WhenLogProgressIsOpenedToday_ThenItOffersTodaysDate', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T12:00:00Z'));
    renderPage();
    await screen.findByText('Cold showers');

    vi.setSystemTime(new Date('2026-03-13T12:00:00Z'));
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect((screen.getByLabelText('Date') as HTMLInputElement).value).toBe('2026-03-13');
  });

  // FR-18 (#95) — the default date is the user's day. The suite runs in Los Angeles, where 22:00 on
  // 11 March is already 12 March in UTC; offered the UTC date, the entry would take tomorrow's one slot
  // (BR-6) and be refused tomorrow.

  it('GivenLateEveningWestOfUtc_WhenLogProgressIsOpened_ThenItOffersTheLocalDay', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect((screen.getByLabelText('Date') as HTMLInputElement).value).toBe('2026-03-11');
  });

  it('GivenLateEveningWestOfUtc_WhenAddReflectionIsOpened_ThenItOffersTheLocalDay', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    expect((screen.getByLabelText('Date') as HTMLInputElement).value).toBe('2026-03-11');
  });

  // FR-18 and FR-23 (#95) — a date the API sends is shown as that day. `new Date('2026-03-12')` is UTC
  // midnight, which in Los Angeles is the evening of the 11th, and was shown as the 11th.

  it('GivenAnEntryAndAReflectionDatedADay_WhenTheDetailsPageRenders_ThenEachShowsThatDay', async () => {
    getProgressByUpgrade.mockResolvedValue([anEntry('p-1', '2026-03-12', 'logged')]);
    getReflectionsByUpgrade.mockResolvedValue([aReflection('r-1', '2026-03-14', 'Went in before breakfast')]);

    renderPage();

    expect(await screen.findByText(new Date(2026, 2, 12).toLocaleDateString())).toBeDefined();
    expect(screen.getByText(new Date(2026, 2, 14).toLocaleDateString())).toBeDefined();
  });

  it('GivenAnUpgradeWithItsDates_WhenTheDetailsPageRenders_ThenEachShowsItsOwnDay', async () => {
    getUpgradeById.mockResolvedValue({
      ...anUpgrade(),
      plannedStartDate: '2026-03-01',
      actualStartDate: '2026-03-02',
      targetEndDate: '2026-04-30',
    });

    renderPage();

    await screen.findByText('Cold showers');
    expect(screen.getByText(labelled('Planned Start:', new Date(2026, 2, 1)))).toBeDefined();
    expect(screen.getByText(labelled('Actual Start:', new Date(2026, 2, 2)))).toBeDefined();
    expect(screen.getByText(labelled('Target End:', new Date(2026, 3, 30)))).toBeDefined();
  });

  it('GivenAProgressEntryWasJustSaved_WhenLogProgressIsOpenedAgain_ThenTheFormIsEmpty', async () => {
    createProgress.mockResolvedValue(anEntry('p-1', '2026-03-12', 'first go'));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.click(screen.getByLabelText('Completed'));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'first go' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect((screen.getByLabelText('Completed') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('');
  });

  it('GivenAProgressEntryWasCancelled_WhenLogProgressIsOpenedAgain_ThenTheFormIsEmpty', async () => {
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'changed my mind' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('');
  });

  it('GivenAProgressEntryWasRefused_WhenLogProgressIsOpenedAgain_ThenTheFormIsEmpty', async () => {
    createProgress.mockRejectedValue(DAY_ALREADY_LOGGED);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'already logged' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(DAY_ALREADY_LOGGED_MESSAGE);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('');
  });

  it('GivenASaveStillInFlight_WhenANewProgressEntryIsStarted_ThenTheEarlierSaveLeavesItAlone', async () => {
    let answerTheSave: (entry: ProgressEntry) => void = () => {};
    createProgress.mockReturnValue(new Promise<ProgressEntry>((resolve) => { answerTheSave = resolve; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.change(screen.getByLabelText('Note'), { target: { value: 'second go' } });
    await act(async () => { answerTheSave(anEntry('p-1', '2026-03-12', 'first go')); });

    expect(screen.getByRole('dialog', { name: 'Log Progress' })).toBeDefined();
    expect((screen.getByLabelText('Note') as HTMLInputElement).value).toBe('second go');
  });

  it('GivenThePageWasOpenedYesterday_WhenAddReflectionIsOpenedToday_ThenItOffersTodaysDate', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T12:00:00Z'));
    renderPage();
    await screen.findByText('Cold showers');

    vi.setSystemTime(new Date('2026-03-13T12:00:00Z'));
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    expect((screen.getByLabelText('Date') as HTMLInputElement).value).toBe('2026-03-13');
  });

  it('GivenAReflectionWasJustSaved_WhenAddReflectionIsOpenedAgain_ThenTheFormIsEmpty', async () => {
    createReflection.mockResolvedValue(aReflection('r-1', '2026-03-12', 'Went in before breakfast'));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.change(screen.getByLabelText('What worked?'), { target: { value: 'Went in before breakfast' } });
    fireEvent.change(screen.getByLabelText('Difficulty (1-5)'), { target: { value: '5' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    for (const note of ['What worked?', "What didn't work?", 'Next adjustment?']) {
      expect((screen.getByLabelText(note) as HTMLTextAreaElement).value).toBe('');
    }
    expect((screen.getByLabelText('Difficulty (1-5)') as HTMLInputElement).value).toBe('3');
  });

  it('GivenAddReflectionIsOpen_WhenItsNoteFieldsAreReached_ThenEachIsNamedByItsLabel', async () => {
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    const dialog = within(screen.getByRole('dialog', { name: 'Add Reflection' }));
    expect(dialog.getByRole('textbox', { name: 'What worked?' })).toBeDefined();
    expect(dialog.getByRole('textbox', { name: "What didn't work?" })).toBeDefined();
    expect(dialog.getByRole('textbox', { name: 'Next adjustment?' })).toBeDefined();
  });

  it('GivenAReflectionWasCancelled_WhenAddReflectionIsOpenedAgain_ThenTheFormIsEmpty', async () => {
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.change(screen.getByLabelText('Difficulty (1-5)'), { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    expect((screen.getByLabelText('Difficulty (1-5)') as HTMLInputElement).value).toBe('3');
  });

  it('GivenASaveStillInFlight_WhenANewReflectionIsStarted_ThenTheEarlierSaveLeavesItAlone', async () => {
    let answerTheSave: (reflection: Reflection) => void = () => {};
    createReflection.mockReturnValue(new Promise<Reflection>((resolve) => { answerTheSave = resolve; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createReflection).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.change(screen.getByLabelText('Difficulty (1-5)'), { target: { value: '5' } });
    await act(async () => { answerTheSave(aReflection('r-1', '2026-03-12', 'Went in before breakfast')); });

    expect(screen.getByRole('dialog', { name: 'Add Reflection' })).toBeDefined();
    expect((screen.getByLabelText('Difficulty (1-5)') as HTMLInputElement).value).toBe('5');
  });

  // NFR-30 (#76) — a refused save says why, in the form it was made in. Each form shows the message on
  // its main field; one with no field to blame carries the trace id that finds it in the log.

  it('GivenADayAlreadyLogged_WhenProgressIsSaved_ThenTheDialogStaysOpenAndSaysWhy', async () => {
    createProgress.mockRejectedValue(DAY_ALREADY_LOGGED);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));

    const dialog = await screen.findByRole('dialog', { name: 'Log Progress' });
    expect(await within(dialog).findByText(DAY_ALREADY_LOGGED_MESSAGE)).toBeDefined();
  });

  it('GivenARatingOutOfRange_WhenTheReflectionIsSaved_ThenTheDialogStaysOpenAndNamesTheField', async () => {
    createReflection.mockRejectedValue(RATING_OUT_OF_RANGE);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));

    const dialog = await screen.findByRole('dialog', { name: 'Add Reflection' });
    expect(await within(dialog).findByText(RATING_OUT_OF_RANGE_MESSAGE)).toBeDefined();
  });

  it('GivenAReflectionWasRefused_WhenAddReflectionIsOpenedAgain_ThenTheRefusalIsGone', async () => {
    createReflection.mockRejectedValue(RATING_OUT_OF_RANGE);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(RATING_OUT_OF_RANGE_MESSAGE);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));

    expect(screen.queryByText(RATING_OUT_OF_RANGE_MESSAGE)).toBeNull();
  });

  it('GivenASaveStillInFlight_WhenANewReflectionIsStartedAndTheSaveIsRefused_ThenTheNewOneSaysNothing', async () => {
    let refuseTheSave: (reason: unknown) => void = () => {};
    createReflection.mockReturnValue(new Promise<Reflection>((_, reject) => { refuseTheSave = reject; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createReflection).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Add Reflection' }));
    await act(async () => { refuseTheSave(RATING_OUT_OF_RANGE); });

    expect(screen.getByRole('dialog', { name: 'Add Reflection' })).toBeDefined();
    expect(screen.queryByText(RATING_OUT_OF_RANGE_MESSAGE)).toBeNull();
  });

  it('GivenAnOverlongUnit_WhenTheTrackingConfigurationIsSaved_ThenTheDialogStaysOpenAndNamesTheField', async () => {
    saveTrackingConfig.mockRejectedValue(OVERLONG_UNIT);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));

    const dialog = await screen.findByRole('dialog', { name: 'Configure Tracking' });
    expect(await within(dialog).findByText(OVERLONG_UNIT_MESSAGE)).toBeDefined();
  });

  it('GivenAValidConfiguration_WhenTrackingIsSaved_ThenTheDialogCloses', async () => {
    saveTrackingConfig.mockResolvedValue(anUpgrade());
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('GivenASavedNumericConfiguration_WhenEditIsOpened_ThenTheFormShowsIt', async () => {
    // Saving starts from what the dialog shows, so defaults here would overwrite the user's target.
    getUpgradeById.mockResolvedValue({
      ...anUpgrade(),
      trackingConfig: {
        id: 'tc-1',
        upgradeId: UPGRADE_ID,
        trackingType: 'NUMERIC',
        frequency: 'WEEKLY',
        targetNumericValue: 10,
        targetUnit: 'minutes',
        requiredDaily: false,
      },
    });
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

    const dialog = screen.getByRole('dialog', { name: 'Configure Tracking' });
    expect((within(dialog).getByLabelText('Tracking type') as HTMLSelectElement).value).toBe('NUMERIC');
    expect((within(dialog).getByLabelText('Frequency') as HTMLSelectElement).value).toBe('WEEKLY');
    expect((within(dialog).getByLabelText('Target value') as HTMLInputElement).value).toBe('10');
    expect((within(dialog).getByLabelText('Unit') as HTMLInputElement).value).toBe('minutes');
    expect((within(dialog).getByLabelText('Required daily') as HTMLInputElement).checked).toBe(false);
  });

  it('GivenATrackingSaveWasRefused_WhenTheDialogIsReopened_ThenTheRefusalIsGone', async () => {
    saveTrackingConfig.mockRejectedValue(OVERLONG_UNIT);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(OVERLONG_UNIT_MESSAGE);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));

    expect(screen.queryByText(OVERLONG_UNIT_MESSAGE)).toBeNull();
  });

  it('GivenNoTime_WhenAReminderIsAdded_ThenItSaysWhy', async () => {
    createReminder.mockRejectedValue(NO_REMINDER_TIME);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));

    expect(await screen.findByText('Must not be null')).toBeDefined();
  });

  it('GivenARefusedReminder_WhenItIsAddedAgainAndAccepted_ThenTheMessageGoes', async () => {
    createReminder
      .mockRejectedValueOnce(NO_REMINDER_TIME)
      .mockResolvedValueOnce({ id: 'r-1', upgradeId: UPGRADE_ID, reminderTime: '09:00:00', daysOfWeek: [], enabled: true });
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));
    await screen.findByText('Must not be null');

    const fetchesBeforeTheRetry = getReminders.mock.calls.length;

    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));

    // A submit clears the message before it is answered, so absence alone proves nothing about the
    // accepted save. The refetch happens only on success; wait for it, then look.
    await waitFor(() => expect(getReminders.mock.calls.length).toBeGreaterThan(fetchesBeforeTheRetry));
    expect(screen.queryByText('Must not be null')).toBeNull();
  });

  it('GivenTheUpgradeIsGone_WhenAReminderIsAdded_ThenItSaysWhyWithItsReference', async () => {
    // A refusal that names no field carries the trace id that finds it (NFR-30).
    createReminder.mockRejectedValue(apiFailure(404, {
      status: 404,
      message: 'Upgrade not found: upgrade-1',
      traceId: 'trace-404',
    }));
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));

    expect(await screen.findByText('Upgrade not found: upgrade-1 (reference trace-404)')).toBeDefined();
  });

  it('GivenTheApiRefusesARemoval_WhenAReminderIsRemoved_ThenTheCardSaysWhyWithItsReference', async () => {
    // NFR-30 (#96). A 404 is what a reminder already removed from another tab answers. Before, the row
    // simply stayed where it was.
    getReminders.mockResolvedValue([{ id: 'reminder-1', upgradeId: UPGRADE_ID, reminderTime: '09:00:00', daysOfWeek: [], enabled: true }]);
    deleteReminder.mockRejectedValue(apiFailure(404, { status: 404, message: 'Reminder not found', traceId: 'trace-131' }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The reminder was not removed.');
    expect(alert.textContent).toContain('Reminder not found');
    expect(alert.textContent).toContain('trace-131');
  });

  it('GivenARemovalInFlight_WhenRemoveIsPressedAgain_ThenNothingMoreIsSent', async () => {
    // A second DELETE would be answered 404 and say "not removed" while the row disappears.
    getReminders.mockResolvedValue([{ id: 'reminder-1', upgradeId: UPGRADE_ID, reminderTime: '09:00:00', daysOfWeek: [], enabled: true }]);
    deleteReminder.mockReturnValue(new Promise(() => {}));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(deleteReminder).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));

    // A mutation calls its function a microtask after `mutate`; give a second call its chance first.
    await act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));
    expect(deleteReminder).toHaveBeenCalledTimes(1);
  });

  it('GivenAProgressEntryWasRefused_WhenLogProgressIsOpenedAgain_ThenTheRefusalIsGone', async () => {
    createProgress.mockRejectedValue(DAY_ALREADY_LOGGED);
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText(DAY_ALREADY_LOGGED_MESSAGE);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));

    expect(screen.queryByText(DAY_ALREADY_LOGGED_MESSAGE)).toBeNull();
  });

  it('GivenASaveStillInFlight_WhenANewProgressEntryIsStartedAndTheSaveIsRefused_ThenTheNewEntrySaysNothing', async () => {
    // The refusal belongs to an entry the user already walked away from.
    let refuseTheSave: (reason: unknown) => void = () => {};
    createProgress.mockReturnValue(new Promise<ProgressEntry>((_, reject) => { refuseTheSave = reject; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createProgress).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: '+ Log Progress' }));
    await act(async () => { refuseTheSave(DAY_ALREADY_LOGGED); });

    expect(screen.getByRole('dialog', { name: 'Log Progress' })).toBeDefined();
    expect(screen.queryByText(DAY_ALREADY_LOGGED_MESSAGE)).toBeNull();
  });

  it('GivenATrackingSaveStillInFlight_WhenTheDialogIsReopenedAndTheSaveIsRefused_ThenTheReopenedDialogSaysNothing', async () => {
    let refuseTheSave: (reason: unknown) => void = () => {};
    saveTrackingConfig.mockReturnValue(new Promise((_, reject) => { refuseTheSave = reject; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveTrackingConfig).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    await act(async () => {
      refuseTheSave(OVERLONG_UNIT);
    });

    expect(screen.getByRole('dialog', { name: 'Configure Tracking' })).toBeDefined();
    expect(screen.queryByText(OVERLONG_UNIT_MESSAGE)).toBeNull();
  });

  it('GivenATrackingSaveStillInFlight_WhenTheDialogIsReopenedAndTheSaveSucceeds_ThenTheReopenedDialogStaysOpen', async () => {
    // Closing on success belongs to the save that asked for it, as it does for progress (#119).
    let answerTheSave: (upgrade: HealthUpgrade) => void = () => {};
    saveTrackingConfig.mockReturnValue(new Promise<HealthUpgrade>((resolve) => { answerTheSave = resolve; }));
    renderPage();
    await screen.findByText('Cold showers');
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    fireEvent.submit(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveTrackingConfig).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Configure' }));
    await act(async () => { answerTheSave(anUpgrade()); });

    expect(screen.getByRole('dialog', { name: 'Configure Tracking' })).toBeDefined();
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
