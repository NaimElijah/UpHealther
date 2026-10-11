import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from 'react-router-dom';
import UpgradeDetailsPage from './UpgradeDetailsPage';
import type { ActionTarget, CreateReminderRequest, CreateUpgradeRequest, HealthArea, HealthUpgrade, ProgressEntry, Reflection, Reminder, UpgradeStatus } from '../types';

const getUpgradeById = vi.fn();
const updateUpgrade = vi.fn();
const performUpgradeAction = vi.fn();
const rescheduleUpgrade = vi.fn();
const deleteUpgrade = vi.fn();
const getProgressByUpgrade = vi.fn();
const createProgress = vi.fn();
const getReflectionsByUpgrade = vi.fn();
const createReflection = vi.fn();
const getReminders = vi.fn();
const createReminder = vi.fn();
const deleteReminder = vi.fn();
const updateReminder = vi.fn();
const saveTrackingConfig = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgradeById: (...a: unknown[]) => getUpgradeById(...a),
  updateUpgrade: (...a: unknown[]) => updateUpgrade(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
  rescheduleUpgrade: (...a: unknown[]) => rescheduleUpgrade(...a),
  deleteUpgrade: (...a: unknown[]) => deleteUpgrade(...a),
}));
vi.mock('../api/healthAreas', () => ({ getHealthAreas: () => Promise.resolve([AN_AREA]) }));
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
  updateReminder: (...a: unknown[]) => updateReminder(...a),
}));
vi.mock('../api/trackingConfig', () => ({ saveTrackingConfig: (...a: unknown[]) => saveTrackingConfig(...a) }));

const UPGRADE_ID = 'upgrade-1';
const OTHER_UPGRADE_ID = 'upgrade-2';
const AN_AREA: HealthArea = { id: 'area-1', userId: 'user-1', name: 'Recovery', createdAt: '2026-03-01T09:00:00' };

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
          <Route path="/dashboard" element={<p>The dashboard</p>} />
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

/**
 * Lets every promise already started run on: a mutation calls its function a microtask after `mutate`,
 * so a check that nothing was sent is only meaningful once that has had its chance.
 */
const settled = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));

/**
 * Answers the upgrade from `initial` until a write changes it, the way the API would, so the page shows
 * whatever the last write left.
 */
function serveUpgrade(initial: HealthUpgrade) {
  let current = initial;
  getUpgradeById.mockImplementation(() => Promise.resolve(current));
  updateUpgrade.mockImplementation((_id: string, req: CreateUpgradeRequest) => {
    current = { ...current, ...req };
    return Promise.resolve(current);
  });
  performUpgradeAction.mockImplementation((_id: string, status: ActionTarget) => {
    current = { ...current, status };
    return Promise.resolve(current);
  });
  // BR-2: rescheduling moves the date in any state, and only from ABANDONED changes the status too.
  rescheduleUpgrade.mockImplementation((_id: string, newDate: string) => {
    current = { ...current, plannedStartDate: newDate, status: current.status === 'ABANDONED' ? 'PLANNED' : current.status };
    return Promise.resolve(current);
  });
}

/** Opens the edit dialog once the page has loaded, and returns queries scoped to it. */
async function openEdit() {
  fireEvent.click(await screen.findByRole('button', { name: 'Edit Upgrade' }));
  return within(screen.getByRole('dialog', { name: 'Edit Upgrade' }));
}

/**
 * FR-13, FR-10 (#89) — an upgrade's descriptive fields can be edited from its page, at any point in its
 * lifecycle.
 *
 * The API replaces every field on a PUT, so the form starts from what the upgrade holds and sends all of
 * it: a field left out would be stored as empty.
 */
describe('UpgradeDetailsPage editing an upgrade', () => {
  const described: HealthUpgrade = {
    ...anUpgrade(),
    areaId: AN_AREA.id,
    description: 'Two minutes, every morning',
    motivation: 'Wake up properly',
    successCriteria: 'Every day for a month',
    targetEndDate: '2026-06-30',
  };

  beforeEach(() => {
    getUpgradeById.mockReset();
    updateUpgrade.mockReset();
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
    getReminders.mockResolvedValue([]);
  });

  it('GivenAnUpgrade_WhenItIsEdited_ThenEveryFieldIsSentAndThePageShowsTheChange', async () => {
    serveUpgrade(described);
    renderPage();
    const dialog = await openEdit();
    await dialog.findByRole('option', { name: AN_AREA.name });

    fireEvent.change(dialog.getByLabelText('Title'), { target: { value: 'Cold plunges' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit Upgrade' })).toBeNull());
    expect(updateUpgrade).toHaveBeenCalledTimes(1);
    expect(updateUpgrade.mock.calls[0][0]).toBe(UPGRADE_ID);
    expect(updateUpgrade.mock.calls[0][1]).toEqual({
      title: 'Cold plunges',
      description: 'Two minutes, every morning',
      type: 'HABIT',
      difficulty: 'MEDIUM',
      areaId: AN_AREA.id,
      motivation: 'Wake up properly',
      successCriteria: 'Every day for a month',
      targetEndDate: '2026-06-30',
    });
    expect(await screen.findByRole('heading', { name: 'Cold plunges' })).toBeDefined();
  });

  it('GivenACompletedUpgrade_WhenItsPageOpens_ThenItCanStillBeEdited', async () => {
    serveUpgrade({ ...described, status: 'COMPLETED' });
    renderPage();

    const dialog = await openEdit();

    expect((dialog.getByLabelText('Title') as HTMLInputElement).value).toBe('Cold showers');
  });

  it('GivenALegacyProtocolUpgrade_WhenItIsEdited_ThenItsTypeIsShownAndKept', async () => {
    // PROTOCOL is no longer offered for new upgrades. Missing from the list, the select would show the
    // first type instead, and the user would read the upgrade as a habit.
    serveUpgrade({ ...described, type: 'PROTOCOL' });
    renderPage();
    const dialog = await openEdit();

    expect((dialog.getByLabelText('Type') as HTMLSelectElement).value).toBe('PROTOCOL');

    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateUpgrade).toHaveBeenCalledTimes(1));
    expect(updateUpgrade.mock.calls[0][1].type).toBe('PROTOCOL');
  });

  it('GivenTheTitleIsCleared_WhenTheEditIsSaved_ThenNothingIsSentAndTheTitleIsAskedFor', async () => {
    serveUpgrade(described);
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Title'), { target: { value: '  ' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));

    expect(await dialog.findByText('Title is required.')).toBeDefined();
    await settled();
    expect(updateUpgrade).not.toHaveBeenCalled();
  });

  it('GivenATargetEndWithAFiveDigitYear_WhenTheEditIsSaved_ThenNothingIsSentAndTheFieldAsksForFourDigits', async () => {
    serveUpgrade(described);
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Target End'), { target: { value: '20260-06-30' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));

    expect(await dialog.findByText('Pick a date with a four-digit year.')).toBeDefined();
    await settled();
    expect(updateUpgrade).not.toHaveBeenCalled();
  });

  it('GivenAnEditTheApiRefuses_WhenItIsSaved_ThenTheDialogSaysWhyWithItsReference', async () => {
    // BR-5: making a running upgrade HARD can exceed the limit, a refusal that names no field.
    serveUpgrade(described);
    updateUpgrade.mockRejectedValue(apiFailure(422, {
      status: 422,
      message: 'Cannot have more than 3 HARD upgrades active at once',
      traceId: 'trace-422',
    }));
    renderPage();
    const dialog = await openEdit();

    fireEvent.change(dialog.getByLabelText('Difficulty'), { target: { value: 'HARD' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Save' }));

    expect(await dialog.findByText('Cannot have more than 3 HARD upgrades active at once (reference trace-422)')).toBeDefined();
    expect(screen.getByRole('dialog', { name: 'Edit Upgrade' })).toBeDefined();
  });

  it('GivenARefusedEdit_WhenTheDialogIsOpenedAgain_ThenItStartsFromTheUpgradeWithoutTheOldMessage', async () => {
    serveUpgrade(described);
    updateUpgrade.mockRejectedValueOnce(apiFailure(422, { status: 422, message: 'Refused', traceId: 'trace-1' }));
    renderPage();
    const first = await openEdit();
    fireEvent.change(first.getByLabelText('Title'), { target: { value: 'Something else' } });
    fireEvent.submit(first.getByRole('button', { name: 'Save' }));
    await first.findByText('Refused (reference trace-1)');
    fireEvent.click(first.getByRole('button', { name: 'Cancel' }));

    const second = await openEdit();

    expect(second.queryByText('Refused (reference trace-1)')).toBeNull();
    expect((second.getByLabelText('Title') as HTMLInputElement).value).toBe('Cold showers');
  });
});

/** Presses Abandon once the page has loaded, and returns queries scoped to the confirmation it opens. */
async function openAbandon() {
  fireEvent.click(await screen.findByRole('button', { name: 'Abandon' }));
  return within(screen.getByRole('dialog', { name: 'Abandon Upgrade' }));
}

/**
 * FR-12 (#89) — an upgrade can be abandoned from its page, from any state that is not final (BR-2).
 *
 * Abandoning asks first: an abandoned upgrade leaves every list, so a slip would look like the upgrade
 * had vanished.
 */
describe('UpgradeDetailsPage abandoning an upgrade', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    updateUpgrade.mockReset();
    performUpgradeAction.mockReset();
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
    getReminders.mockResolvedValue([]);
  });

  it.each<UpgradeStatus>(['IDEA', 'PLANNED', 'ACTIVE', 'PAUSED'])(
    'GivenA%sUpgrade_WhenAbandonIsConfirmed_ThenItIsAbandoned',
    async (status) => {
      serveUpgrade({ ...anUpgrade(), status });
      renderPage();
      const dialog = await openAbandon();

      fireEvent.click(dialog.getByRole('button', { name: 'Abandon' }));

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());
      expect(performUpgradeAction).toHaveBeenCalledTimes(1);
      expect(performUpgradeAction.mock.calls[0]).toEqual([UPGRADE_ID, 'ABANDONED']);
      expect(await screen.findByText('Abandoned')).toBeDefined();
    },
  );

  it.each<UpgradeStatus>(['COMPLETED', 'ABANDONED'])(
    'GivenA%sUpgrade_WhenItsPageOpens_ThenAbandonIsNotOffered',
    async (status) => {
      serveUpgrade({ ...anUpgrade(), status });
      renderPage();

      await screen.findByRole('button', { name: 'Edit Upgrade' });

      expect(screen.queryByRole('button', { name: 'Abandon' })).toBeNull();
    },
  );

  it('GivenTheConfirmationIsCancelled_WhenItCloses_ThenNothingIsAbandoned', async () => {
    serveUpgrade(anUpgrade());
    renderPage();
    const dialog = await openAbandon();

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());
    await settled();
    expect(performUpgradeAction).not.toHaveBeenCalled();
  });

  // FR-40: a dialog gives focus back on close. Abandoning removes the button that opened this one, so
  // focus would fall to <body> and a keyboard user would start again from the top of the page.
  it('GivenAConfirmedAbandon_WhenTheDialogCloses_ThenFocusIsOnReschedule', async () => {
    serveUpgrade(anUpgrade());
    renderPage();
    const trigger = await screen.findByRole('button', { name: 'Abandon' });
    // `fireEvent.click` does not move focus in jsdom; a keyboard user's press would.
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = within(screen.getByRole('dialog', { name: 'Abandon Upgrade' }));

    fireEvent.click(dialog.getByRole('button', { name: 'Abandon' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Reschedule' })));
  });

  it('GivenTheAbandonIsCancelled_WhenTheDialogCloses_ThenFocusReturnsToAbandon', async () => {
    serveUpgrade(anUpgrade());
    renderPage();
    const trigger = await screen.findByRole('button', { name: 'Abandon' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = within(screen.getByRole('dialog', { name: 'Abandon Upgrade' }));

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());
    expect(document.activeElement).toBe(trigger);
  });

  it('GivenAnAbandonInFlight_WhenTheDialogIsDismissed_ThenItStaysOpenUntilTheAnswerArrives', async () => {
    // Dismissed mid-flight, it could be opened again and the abandon sent twice; the second would be
    // refused 422, reading as though the first had failed.
    let answer: () => void = () => {};
    serveUpgrade(anUpgrade());
    performUpgradeAction.mockImplementationOnce(() => new Promise<HealthUpgrade>((resolve) => {
      answer = () => resolve({ ...anUpgrade(), status: 'ABANDONED' });
    }));
    renderPage();
    const dialog = await openAbandon();
    fireEvent.click(dialog.getByRole('button', { name: 'Abandon' }));
    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledTimes(1));

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByRole('dialog', { name: 'Abandon Upgrade' })).toBeDefined();

    await act(async () => answer());

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Abandon Upgrade' })).toBeNull());
    expect(performUpgradeAction).toHaveBeenCalledTimes(1);
  });

  it('GivenAnAbandonTheApiRefuses_WhenItIsConfirmed_ThenTheDialogSaysWhyWithItsReference', async () => {
    serveUpgrade(anUpgrade());
    performUpgradeAction.mockRejectedValue(apiFailure(422, {
      status: 422,
      message: 'Cannot abandon a COMPLETED upgrade',
      traceId: 'trace-abandon',
    }));
    renderPage();
    const dialog = await openAbandon();

    fireEvent.click(dialog.getByRole('button', { name: 'Abandon' }));

    expect(await dialog.findByText('Cannot abandon a COMPLETED upgrade')).toBeDefined();
    expect(dialog.getByText('trace-abandon')).toBeDefined();
    expect(screen.getByRole('dialog', { name: 'Abandon Upgrade' })).toBeDefined();
  });
});

/** Presses Reschedule once the page has loaded, and returns queries scoped to the dialog it opens. */
async function openReschedule() {
  fireEvent.click(await screen.findByRole('button', { name: 'Reschedule' }));
  return within(screen.getByRole('dialog', { name: 'Reschedule Upgrade' }));
}

/**
 * FR-12 (#89) — an upgrade can be rescheduled from its page, in any state but COMPLETED (BR-2, BR-3).
 *
 * Rescheduling moves the planned start. From ABANDONED it is also the only way back, to PLANNED, which
 * makes it the one way to undo an abandon.
 */
describe('UpgradeDetailsPage rescheduling an upgrade', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    rescheduleUpgrade.mockReset();
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
    getReminders.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('GivenAnAbandonedUpgrade_WhenItIsRescheduled_ThenItIsPlannedAgain', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'ABANDONED' });
    renderPage();
    const dialog = await openReschedule();
    expect(dialog.getByText(/goes back to Planned/)).toBeDefined();

    fireEvent.change(dialog.getByLabelText('New start date'), { target: { value: '2026-11-01' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Reschedule Upgrade' })).toBeNull());
    expect(rescheduleUpgrade.mock.calls[0]).toEqual([UPGRADE_ID, '2026-11-01']);
    expect(await screen.findByText('Planned')).toBeDefined();
  });

  it('GivenAPlannedUpgrade_WhenRescheduleOpens_ThenItStartsFromThePlannedStart', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'PLANNED', plannedStartDate: '2026-10-20' });
    renderPage();

    const dialog = await openReschedule();

    expect((dialog.getByLabelText('New start date') as HTMLInputElement).value).toBe('2026-10-20');
  });

  // The suite runs in Los Angeles, where 05:00 UTC on 12 March is still the evening of 11 March (#95).
  it('GivenNoPlannedStart_WhenRescheduleOpens_ThenItStartsFromTheUsersToday', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));
    serveUpgrade({ ...anUpgrade(), status: 'ABANDONED' });
    renderPage();

    const dialog = await openReschedule();

    expect((dialog.getByLabelText('New start date') as HTMLInputElement).value).toBe('2026-03-11');
  });

  // COMPLETED is terminal (BR-3). An idea is planned rather than rescheduled: rescheduling one would set a
  // start date and leave it an idea, listed with the ideas, until Plan overwrote the date.
  it.each<UpgradeStatus>(['IDEA', 'COMPLETED'])(
    'GivenA%sUpgrade_WhenItsPageOpens_ThenRescheduleIsNotOffered',
    async (status) => {
      serveUpgrade({ ...anUpgrade(), status });
      renderPage();

      await screen.findByRole('button', { name: 'Edit Upgrade' });

      expect(screen.queryByRole('button', { name: 'Reschedule' })).toBeNull();
    },
  );

  it.each<UpgradeStatus>(['PLANNED', 'ACTIVE', 'PAUSED', 'ABANDONED'])(
    'GivenA%sUpgrade_WhenItIsRescheduled_ThenTheNewDateIsSent',
    async (status) => {
      serveUpgrade({ ...anUpgrade(), status, plannedStartDate: '2026-10-20' });
      renderPage();
      const dialog = await openReschedule();

      fireEvent.change(dialog.getByLabelText('New start date'), { target: { value: '2026-11-01' } });
      fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Reschedule Upgrade' })).toBeNull());
      expect(rescheduleUpgrade.mock.calls[0]).toEqual([UPGRADE_ID, '2026-11-01']);
      expect(await screen.findByText(/11\/1\/2026/)).toBeDefined();
    },
  );

  it('GivenTheDateIsCleared_WhenTheRescheduleIsSubmitted_ThenNothingIsSentAndADateIsAskedFor', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'ABANDONED' });
    renderPage();
    const dialog = await openReschedule();

    fireEvent.change(dialog.getByLabelText('New start date'), { target: { value: '' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));

    expect(await dialog.findByText('Pick a start date.')).toBeDefined();
    await settled();
    expect(rescheduleUpgrade).not.toHaveBeenCalled();
  });

  it('GivenAFiveDigitYear_WhenTheRescheduleIsSubmitted_ThenNothingIsSentAndTheDialogAsksForFourDigits', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'ABANDONED' });
    renderPage();
    const dialog = await openReschedule();

    fireEvent.change(dialog.getByLabelText('New start date'), { target: { value: '20261-11-01' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));

    expect(await dialog.findByText('Pick a date with a four-digit year.')).toBeDefined();
    await settled();
    expect(rescheduleUpgrade).not.toHaveBeenCalled();
  });

  it('GivenARescheduleTheApiRefuses_WhenItIsSubmitted_ThenTheDialogSaysWhyWithItsReference', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'PAUSED' });
    rescheduleUpgrade.mockRejectedValue(apiFailure(422, {
      status: 422,
      message: 'Cannot reschedule a COMPLETED upgrade',
      traceId: 'trace-reschedule',
    }));
    renderPage();
    const dialog = await openReschedule();

    fireEvent.submit(dialog.getByRole('button', { name: 'Reschedule' }));

    expect(await dialog.findByText('Cannot reschedule a COMPLETED upgrade (reference trace-reschedule)')).toBeDefined();
  });
});

/** Presses Delete once the page has loaded, and returns queries scoped to the confirmation it opens. */
async function openDelete() {
  fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
  return within(screen.getByRole('dialog', { name: 'Delete Upgrade' }));
}

/**
 * FR-14 (#89) — an upgrade can be deleted from its page, in any state, with everything recorded against
 * it.
 *
 * Deleting cannot be undone, so it asks first and says what goes with the upgrade. Once it is gone its
 * page has nothing left to show, so the dashboard opens in its place.
 */
describe('UpgradeDetailsPage deleting an upgrade', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    deleteUpgrade.mockReset();
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
    getReminders.mockResolvedValue([]);
    deleteUpgrade.mockResolvedValue(undefined);
  });

  it('GivenAConfirmedDelete_WhenItSucceeds_ThenTheUpgradeIsDeletedAndTheDashboardOpens', async () => {
    serveUpgrade(anUpgrade());
    renderPage();
    const dialog = await openDelete();
    expect(dialog.getByText(/progress, reminders and reflections/)).toBeDefined();

    fireEvent.click(dialog.getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('The dashboard')).toBeDefined();
    expect(deleteUpgrade.mock.calls[0][0]).toBe(UPGRADE_ID);
    // Read once, to show it: the refresh after the delete must not ask for it again, into a 404.
    expect(getUpgradeById).toHaveBeenCalledTimes(1);
  });

  it('GivenACompletedUpgrade_WhenItsPageOpens_ThenItCanStillBeDeleted', async () => {
    serveUpgrade({ ...anUpgrade(), status: 'COMPLETED' });
    renderPage();

    expect(await screen.findByRole('button', { name: 'Delete' })).toBeDefined();
  });

  it('GivenTheConfirmationIsCancelled_WhenItCloses_ThenNothingIsDeleted', async () => {
    serveUpgrade(anUpgrade());
    renderPage();
    const dialog = await openDelete();

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete Upgrade' })).toBeNull());
    await settled();
    expect(deleteUpgrade).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Cold showers' })).toBeDefined();
  });

  it('GivenADeleteInFlight_WhenTheDialogIsDismissed_ThenItStaysOpenUntilTheAnswerArrives', async () => {
    // Dismissed mid-flight, the dialog could be opened again and the delete sent twice; the second would
    // be answered 404, reading as though the first had failed.
    let answer: () => void = () => {};
    deleteUpgrade.mockImplementation(() => new Promise<void>((resolve) => { answer = resolve; }));
    serveUpgrade(anUpgrade());
    renderPage();
    const dialog = await openDelete();
    fireEvent.click(dialog.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deleteUpgrade).toHaveBeenCalledTimes(1));

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByRole('dialog', { name: 'Delete Upgrade' })).toBeDefined();

    await act(async () => answer());

    expect(await screen.findByText('The dashboard')).toBeDefined();
  });

  it('GivenADeleteTheApiRefuses_WhenItIsConfirmed_ThenTheDialogSaysWhyAndThePageStays', async () => {
    serveUpgrade(anUpgrade());
    deleteUpgrade.mockRejectedValue(apiFailure(500, {
      status: 500,
      message: 'An unexpected error occurred',
      traceId: 'trace-delete',
    }));
    renderPage();
    const dialog = await openDelete();

    fireEvent.click(dialog.getByRole('button', { name: 'Delete' }));

    expect(await dialog.findByText('An unexpected error occurred')).toBeDefined();
    expect(dialog.getByText('trace-delete')).toBeDefined();
    expect(screen.queryByText('The dashboard')).toBeNull();
  });
});

/** A reminder as the API sends one: its time with seconds, and its days in week order. */
function aReminder(values: Partial<Reminder> = {}): Reminder {
  return { id: 'reminder-1', upgradeId: UPGRADE_ID, reminderTime: '09:00:00', daysOfWeek: ['MON'], enabled: true, ...values };
}

/**
 * Answers the upgrade's reminders from `initial` until an update changes one, the way the API would. As
 * the API does, an update that leaves `enabled` out leaves the reminder's state alone.
 */
function serveReminders(...initial: Reminder[]) {
  let reminders = initial;
  getReminders.mockImplementation(() => Promise.resolve(reminders));
  updateReminder.mockImplementation((id: string, req: CreateReminderRequest) => {
    reminders = reminders.map((r) => (r.id === id
      ? { ...r, reminderTime: `${req.reminderTime.slice(0, 5)}:00`, daysOfWeek: req.daysOfWeek ?? [], enabled: req.enabled ?? r.enabled }
      : r));
    return Promise.resolve(reminders.find((r) => r.id === id));
  });
}

/**
 * FR-26 (#89) — a reminder can be rescheduled, switched off and switched back on from its upgrade's page.
 *
 * Each row's controls name the reminder they act on, since an upgrade can have several and the page
 * already has an "Edit" for its tracking.
 */
describe('UpgradeDetailsPage changing a reminder', () => {
  beforeEach(() => {
    getUpgradeById.mockReset();
    getReminders.mockReset();
    updateReminder.mockReset();
    createReminder.mockReset();
    getUpgradeById.mockResolvedValue(anUpgrade());
    getProgressByUpgrade.mockResolvedValue([]);
    getReflectionsByUpgrade.mockResolvedValue([]);
  });

  it('GivenAReminder_WhenItIsRescheduled_ThenItsNewTimeAndDaysAreSentAndItsStateIsLeftAlone', async () => {
    serveReminders(aReminder({ enabled: false }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Edit the 09:00 reminder' }));
    expect((screen.getByLabelText('Time') as HTMLInputElement).value).toBe('09:00');
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '07:30' } });
    fireEvent.click(screen.getByTitle('WED'));
    fireEvent.submit(screen.getByRole('button', { name: 'Save reminder' }));

    expect(await screen.findByRole('button', { name: 'Edit the 07:30 reminder' })).toBeDefined();
    expect(updateReminder).toHaveBeenCalledTimes(1);
    expect(updateReminder.mock.calls[0][0]).toBe('reminder-1');
    expect(updateReminder.mock.calls[0][1]).toEqual({ reminderTime: '07:30', daysOfWeek: ['MON', 'WED'] });
    expect(screen.getByRole('button', { name: 'Add reminder' })).toBeDefined();
    expect(createReminder).not.toHaveBeenCalled();
  });

  it('GivenAReminderBeingRescheduled_WhenTheEditIsCancelled_ThenNothingIsSentAndTheFormAddsAgain', async () => {
    serveReminders(aReminder());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit the 09:00 reminder' }));
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '07:30' } });

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('button', { name: 'Add reminder' })).toBeDefined();
    expect((screen.getByLabelText('Time') as HTMLInputElement).value).toBe('09:00');
    await settled();
    expect(updateReminder).not.toHaveBeenCalled();
  });

  it('GivenAnEnabledReminder_WhenItIsDisabled_ThenItIsSentDisabledWithItsScheduleUnchanged', async () => {
    serveReminders(aReminder());
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Disable the 09:00 reminder' }));

    expect(await screen.findByRole('button', { name: 'Enable the 09:00 reminder' })).toBeDefined();
    expect(updateReminder.mock.calls[0]).toEqual(['reminder-1', { reminderTime: '09:00:00', daysOfWeek: ['MON'], enabled: false }]);
    expect(screen.getByText('disabled')).toBeDefined();
  });

  it('GivenADisabledReminder_WhenItIsEnabled_ThenItIsSentEnabled', async () => {
    serveReminders(aReminder({ enabled: false }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Enable the 09:00 reminder' }));

    expect(await screen.findByRole('button', { name: 'Disable the 09:00 reminder' })).toBeDefined();
    expect(updateReminder.mock.calls[0][1]).toEqual({ reminderTime: '09:00:00', daysOfWeek: ['MON'], enabled: true });
    expect(screen.queryByText('disabled')).toBeNull();
  });

  it('GivenTheApiRefusesASwitch_WhenAReminderIsDisabled_ThenTheCardSaysWhyWithItsReference', async () => {
    serveReminders(aReminder());
    updateReminder.mockRejectedValue(apiFailure(404, { status: 404, message: 'Reminder not found', traceId: 'trace-switch' }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Disable the 09:00 reminder' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The reminder was not changed.');
    expect(alert.textContent).toContain('Reminder not found');
    expect(alert.textContent).toContain('trace-switch');
  });

  // One write to the reminders at a time. A switch resends the schedule its row shows, and the form edits
  // from a row, so either acting while another write is in flight works from a schedule about to change.

  it('GivenARescheduleInFlight_WhenItsReminderIsSwitched_ThenTheSwitchWaitsAndTheRescheduleStands', async () => {
    serveReminders(aReminder());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit the 09:00 reminder' }));
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '07:30' } });
    updateReminder.mockImplementationOnce(() => new Promise(() => {}));
    fireEvent.submit(screen.getByRole('button', { name: 'Save reminder' }));
    await waitFor(() => expect(updateReminder).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Disable the 09:00 reminder' }));

    await settled();
    expect(updateReminder).toHaveBeenCalledTimes(1);
  });

  it('GivenAnAddInFlight_WhenAReminderIsEdited_ThenTheFormKeepsAdding', async () => {
    // The add's success clears the form's days; an edit opened under it would lose its own.
    serveReminders(aReminder());
    createReminder.mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByRole('button', { name: 'Edit the 09:00 reminder' });
    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));
    await waitFor(() => expect(createReminder).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Edit the 09:00 reminder' }));

    expect(screen.queryByRole('button', { name: 'Save reminder' })).toBeNull();
  });

  it('GivenARescheduleInFlight_WhenCancelIsPressed_ThenTheEditStaysUntilTheAnswer', async () => {
    // Cancelled mid-flight, the save's success would reset whatever had been typed since, and its
    // refusal would never be shown.
    serveReminders(aReminder());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit the 09:00 reminder' }));
    updateReminder.mockImplementationOnce(() => new Promise(() => {}));
    fireEvent.submit(screen.getByRole('button', { name: 'Save reminder' }));
    await waitFor(() => expect(updateReminder).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('button', { name: 'Save reminder' })).toBeDefined();
  });

  it('GivenARefusedAdd_WhenAReminderIsEditedAndTheEditCancelled_ThenTheOldRefusalIsGone', async () => {
    serveReminders(aReminder());
    createReminder.mockRejectedValue(NO_REMINDER_TIME);
    renderPage();
    await screen.findByRole('button', { name: 'Edit the 09:00 reminder' });
    fireEvent.submit(screen.getByRole('button', { name: 'Add reminder' }));
    await screen.findByText('Must not be null');

    fireEvent.click(screen.getByRole('button', { name: 'Edit the 09:00 reminder' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByText('Must not be null')).toBeNull();
  });

  it('GivenTheApiRefusesAReschedule_WhenItIsSaved_ThenTheFormSaysWhyAndKeepsTheEdit', async () => {
    serveReminders(aReminder());
    updateReminder.mockRejectedValue(NO_REMINDER_TIME);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit the 09:00 reminder' }));

    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Save reminder' }));

    expect(await screen.findByText('Must not be null')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Save reminder' })).toBeDefined();
  });
});
