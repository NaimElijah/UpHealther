import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import UpgradeBacklogPage from './UpgradeBacklogPage';
import type { HealthUpgrade } from '../types';
import { upgradeAsSent } from '../test/wireFixtures';
import { headingOutlineProblems } from '../test/headingOutline';
import { enterUnreadableText } from '../test/unreadableInput';

const getUpgrades = vi.fn();
const planUpgrade = vi.fn();
const createUpgrade = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  planUpgrade: (...a: unknown[]) => planUpgrade(...a),
  createUpgrade: (...a: unknown[]) => createUpgrade(...a),
}));

vi.mock('../api/healthAreas', () => ({
  getHealthAreas: () => Promise.resolve([]),
}));

const UPGRADE_ID = 'upgrade-1';

function anIdea(values: Partial<HealthUpgrade> = {}): HealthUpgrade {
  return upgradeAsSent({
    id: UPGRADE_ID,
    userId: 'user-1',
    title: 'Cold showers',
    type: 'HABIT',
    status: 'IDEA',
    difficulty: 'MEDIUM',
    createdAt: '2026-03-01T09:00:00',
    version: 0,
    ...values,
  });
}

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <UpgradeBacklogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Lets every promise already started run on: a mutation calls its function a microtask after `mutate`,
 * so a check that nothing was sent is only meaningful once that has had its chance.
 */
const settled = () => act(() => new Promise<void>((resolve) => setTimeout(resolve, 0)));

/** Opens the new-idea dialog with a title typed in, and returns queries scoped to it. */
async function openCreate(title = 'Run a 5k') {
  fireEvent.click(await screen.findByRole('button', { name: '+ New Idea' }));
  const dialog = within(screen.getByRole('dialog', { name: 'New Upgrade Idea' }));
  fireEvent.change(dialog.getByLabelText('Title'), { target: { value: title } });
  return dialog;
}

/** Presses Plan on the only idea listed, and returns queries scoped to the dialog it opens. */
async function openPlan() {
  await screen.findByText('Cold showers');
  fireEvent.click(screen.getByRole('button', { name: 'Plan Cold showers' }));
  return within(screen.getByRole('dialog', { name: 'Plan Upgrade' }));
}

/**
 * FR-12 (#88) — an idea leaves `IDEA` by being planned, and planning needs a start date.
 *
 * The API refuses a plan without one (`PlanRequest`), and every later state is reached through
 * `PLANNED` (BR-2). So a Plan button that sends no date strands everything created in the interface as
 * an idea, and one whose refusal is not shown makes that look like a click that did not register.
 */
describe('UpgradeBacklogPage', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    planUpgrade.mockReset();
    getUpgrades.mockResolvedValue([anIdea()]);
    planUpgrade.mockResolvedValue(anIdea({ status: 'PLANNED' }));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // The suite runs in Los Angeles, where 05:00 UTC on 12 March is still the evening of 11 March. The
  // default is the user's day, not the UTC one (#95).
  it('GivenAnIdea_WhenPlanIsPressed_ThenADialogAsksForAStartDateDefaultingToToday', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));
    renderPage();

    const dialog = await openPlan();

    expect((dialog.getByLabelText('Start date') as HTMLInputElement).value).toBe('2026-03-11');
  });

  it('GivenAStartDate_WhenThePlanIsSubmitted_ThenItIsSentAndTheDialogCloses', async () => {
    renderPage();
    const dialog = await openPlan();

    fireEvent.change(dialog.getByLabelText('Start date'), { target: { value: '2026-04-01' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    await waitFor(() => expect(planUpgrade).toHaveBeenCalledWith(UPGRADE_ID, '2026-04-01'));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Plan Upgrade' })).toBeNull());
  });

  it('GivenTheApiRefusesThePlan_WhenItIsSubmitted_ThenTheDialogSaysWhyWithTheReference', async () => {
    // A 422 is what the API answers when the upgrade is no longer an idea, for example because it was
    // planned from another tab. It names no field, so the message carries the trace id (NFR-30).
    planUpgrade.mockRejectedValue(
      apiFailure(422, { status: 422, message: 'Only IDEA upgrades can be planned', traceId: 'trace-88' }),
    );
    renderPage();
    const dialog = await openPlan();

    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    expect(await dialog.findByText('Only IDEA upgrades can be planned (reference trace-88)')).toBeDefined();
    expect(screen.getByRole('dialog', { name: 'Plan Upgrade' })).toBeDefined();
  });

  it('GivenTheDateIsCleared_WhenThePlanIsSubmitted_ThenNothingIsSentAndTheDialogAsksForOne', async () => {
    renderPage();
    const dialog = await openPlan();

    fireEvent.change(dialog.getByLabelText('Start date'), { target: { value: '' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    expect(await dialog.findByText('Pick a start date.')).toBeDefined();
    expect(planUpgrade).not.toHaveBeenCalled();
  });

  it('GivenAFiveDigitYear_WhenThePlanIsSubmitted_ThenNothingIsSentAndTheDialogAsksForFourDigits', async () => {
    // A date field accepts years past 9999, and gives them without the sign `LocalDate` needs to read
    // them (`+20260-01-01`), so the API would refuse the body with no field to name.
    renderPage();
    const dialog = await openPlan();

    fireEvent.change(dialog.getByLabelText('Start date'), { target: { value: '20260-01-01' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    expect(await dialog.findByText('Pick a date with a four-digit year.')).toBeDefined();
    expect(planUpgrade).not.toHaveBeenCalled();
  });

  it('GivenAPlanInFlight_WhenTheDialogIsDismissed_ThenItStaysOpenUntilTheAnswerArrives', async () => {
    // One mutation serves every idea. Dismissed mid-flight, the dialog would let another idea's open
    // before this answer lands; the answer would then close that one, or put this refusal in it.
    let answer: (planned: HealthUpgrade) => void = () => {};
    planUpgrade.mockImplementation(
      () =>
        new Promise<HealthUpgrade>((resolve) => {
          answer = resolve;
        }),
    );
    renderPage();
    const dialog = await openPlan();
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));
    await waitFor(() => expect(planUpgrade).toHaveBeenCalledTimes(1));

    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    fireEvent.click(dialog.getByRole('button', { name: 'Close modal' }));
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByRole('dialog', { name: 'Plan Upgrade' })).toBeDefined();

    await act(async () => answer(anIdea({ status: 'PLANNED' })));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Plan Upgrade' })).toBeNull());
  });

  it('GivenAPlannedIdea_WhenTheBacklogIsStillRefreshing_ThenTheDialogWaitsForTheNewList', async () => {
    // Closed any sooner, the dialog would uncover the planned idea still listed with its Plan button,
    // and planning it again would earn a 422 that reads as though the first plan had failed.
    let refreshed: (ideas: HealthUpgrade[]) => void = () => {};
    getUpgrades.mockResolvedValueOnce([anIdea()]).mockImplementationOnce(
      () =>
        new Promise<HealthUpgrade[]>((resolve) => {
          refreshed = resolve;
        }),
    );
    renderPage();
    const dialog = await openPlan();

    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));
    await waitFor(() => expect(getUpgrades).toHaveBeenCalledTimes(2));

    expect(screen.getByRole('dialog', { name: 'Plan Upgrade' })).toBeDefined();

    await act(async () => refreshed([]));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Plan Upgrade' })).toBeNull());
    expect(screen.queryByText('Cold showers')).toBeNull();
  });

  it('GivenARefusedPlan_WhenTheDialogIsOpenedAgain_ThenItStartsWithoutTheOldMessage', async () => {
    planUpgrade.mockRejectedValueOnce(apiFailure(422, { status: 422, message: 'Only IDEA upgrades can be planned' }));
    renderPage();
    const first = await openPlan();
    fireEvent.submit(first.getByRole('button', { name: 'Plan' }));
    await first.findByText('Only IDEA upgrades can be planned');
    fireEvent.click(first.getByRole('button', { name: 'Cancel' }));

    const second = await openPlan();

    expect(second.queryByText('Only IDEA upgrades can be planned')).toBeNull();
  });
});

/**
 * FR-10, FR-29 (#89) — a new idea can carry its success criteria and the date it should be done by.
 *
 * Neither could be set anywhere in the interface, so no upgrade made through it could ever be overdue
 * (FR-29), and "how will I know it worked" had nowhere to go.
 */
describe('UpgradeBacklogPage creating an idea', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    createUpgrade.mockReset();
    getUpgrades.mockResolvedValue([]);
    createUpgrade.mockResolvedValue(anIdea());
  });

  it('GivenSuccessCriteriaAndATargetEnd_WhenAnIdeaIsCreated_ThenBothAreSent', async () => {
    renderPage();
    const dialog = await openCreate();
    fireEvent.change(dialog.getByLabelText('Success Criteria'), { target: { value: 'Finish under 30 minutes' } });
    fireEvent.change(dialog.getByLabelText('Target End'), { target: { value: '2026-12-31' } });

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    await waitFor(() => expect(createUpgrade).toHaveBeenCalledTimes(1));
    expect(createUpgrade.mock.calls[0][0]).toMatchObject({
      title: 'Run a 5k',
      successCriteria: 'Finish under 30 minutes',
      targetEndDate: '2026-12-31',
    });
  });

  it('GivenNoTargetEnd_WhenAnIdeaIsCreated_ThenNoDateIsSent', async () => {
    renderPage();
    const dialog = await openCreate();
    fireEvent.change(dialog.getByLabelText('Target End'), { target: { value: '2026-12-31' } });
    fireEvent.change(dialog.getByLabelText('Target End'), { target: { value: '' } });

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    await waitFor(() => expect(createUpgrade).toHaveBeenCalledTimes(1));
    expect(createUpgrade.mock.calls[0][0].targetEndDate).toBeUndefined();
  });

  it('GivenATargetEndWithAFiveDigitYear_WhenAnIdeaIsCreated_ThenNothingIsSentAndTheFieldAsksForFourDigits', async () => {
    // A date field accepts years past 9999; the API reads one only with a sign, and would refuse the body
    // without naming the field.
    renderPage();
    const dialog = await openCreate();
    fireEvent.change(dialog.getByLabelText('Target End'), { target: { value: '20260-12-31' } });

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    expect(await dialog.findByText('Pick a date with a four-digit year.')).toBeDefined();
    await settled();
    expect(createUpgrade).not.toHaveBeenCalled();
  });

  it('GivenATitleTheApiRefuses_WhenAnIdeaIsCreated_ThenTheDialogStaysOpenAndNamesTheReason', async () => {
    // BR-16: the API refuses an over-long title with a 400 naming the field.
    createUpgrade.mockRejectedValue(
      apiFailure(400, { message: 'Validation failed', fieldErrors: { title: 'size must be between 0 and 255' } }),
    );
    renderPage();
    const dialog = await openCreate();

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    expect(await dialog.findByText('Size must be between 0 and 255')).toBeDefined();
    expect(screen.getByRole('dialog', { name: 'New Upgrade Idea' })).toBeDefined();
  });

  it('GivenACreateWasRefused_WhenTheDialogIsOpenedAgain_ThenItStartsWithoutTheOldMessage', async () => {
    renderPage();
    const first = await openCreate('');
    fireEvent.submit(first.getByRole('button', { name: 'Add Idea' }));
    await first.findByText('Title is required.');
    fireEvent.click(first.getByRole('button', { name: 'Cancel' }));

    const again = await openCreate();

    expect(again.queryByText('Title is required.')).toBeNull();
  });

  it('GivenATargetEndWasRefused_WhenTheDialogIsOpenedAgain_ThenItStartsWithoutTheOldMessage', async () => {
    renderPage();
    const first = await openCreate();
    fireEvent.change(first.getByLabelText('Target End'), { target: { value: '20260-12-31' } });
    fireEvent.submit(first.getByRole('button', { name: 'Add Idea' }));
    await first.findByText('Pick a date with a four-digit year.');
    fireEvent.click(first.getByRole('button', { name: 'Cancel' }));

    const again = await openCreate();

    expect(again.queryByText('Pick a date with a four-digit year.')).toBeNull();
  });

  it('GivenACreateWasRefused_WhenTheDialogIsOpenedFromAddFirstIdea_ThenItStartsWithoutTheOldMessage', async () => {
    renderPage();
    const first = await openCreate('');
    fireEvent.submit(first.getByRole('button', { name: 'Add Idea' }));
    await first.findByText('Title is required.');
    fireEvent.click(first.getByRole('button', { name: 'Cancel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Add First Idea' }));

    const again = within(screen.getByRole('dialog', { name: 'New Upgrade Idea' }));
    expect(again.queryByText('Title is required.')).toBeNull();
  });

  it('GivenARefusalThatNamesNoField_WhenAnIdeaIsCreated_ThenTheDialogSaysWhyWithTheReference', async () => {
    createUpgrade.mockRejectedValue(
      apiFailure(500, { status: 500, message: 'An unexpected error occurred', traceId: 'trace-create' }),
    );
    renderPage();
    const dialog = await openCreate();

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    expect(await dialog.findByText('An unexpected error occurred (reference trace-create)')).toBeDefined();
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('UpgradeBacklogPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';
    getUpgrades.mockResolvedValue([]);

    renderPage();

    expect(document.title).toBe('Idea Backlog – UpHealther');
  });
});

/**
 * Planning an idea takes it off the backlog, and its Plan button with it, so the dialog had nothing to
 * hand focus back to and it fell to `<body>`. It goes to the page's heading instead (WCAG 2.4.3,
 * finding X9.4).
 */
describe('UpgradeBacklogPage focus after planning', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    planUpgrade.mockReset();
  });

  it('GivenAnIdea_WhenItIsPlanned_ThenFocusMovesToThePageHeading', async () => {
    getUpgrades.mockResolvedValueOnce([anIdea()]).mockResolvedValue([]);
    planUpgrade.mockResolvedValue(anIdea({ status: 'PLANNED' }));
    renderPage();
    await screen.findByText('Cold showers');
    const plan = screen.getByRole('button', { name: 'Plan Cold showers' });
    plan.focus();
    fireEvent.click(plan);
    const dialog = within(screen.getByRole('dialog', { name: 'Plan Upgrade' }));

    fireEvent.click(dialog.getByRole('button', { name: 'Plan' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })));
  });
});

/**
 * The backlog's dialogs mark their required fields, and a refused submit sends focus to the field to fix
 * (WCAG 3.3.1, 3.3.2, finding X9.5).
 */
describe('UpgradeBacklogPage form errors', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    planUpgrade.mockReset();
    createUpgrade.mockReset();
    getUpgrades.mockResolvedValue([anIdea()]);
  });

  // `fireEvent.submit` skips the browser's own validation, so without this a form that lost `noValidate`
  // passed every test, while in a browser the native bubble took the place of the form's message and focus
  // (review finding 16 on #407).
  it('GivenEitherBacklogDialog_WhenItOpens_ThenItsFormChecksItselfRatherThanTheBrowser', async () => {
    renderPage();
    const create = await openCreate();
    expect(create.getByLabelText('Title').closest('form')?.noValidate).toBe(true);
    fireEvent.click(create.getByRole('button', { name: 'Cancel' }));

    const plan = await openPlan();

    expect(plan.getByLabelText('Start date').closest('form')?.noValidate).toBe(true);
  });

  it('GivenTheNewIdeaDialog_WhenItIsSubmittedWithoutATitle_ThenTheTitleIsRequiredInvalidAndHasFocus', async () => {
    renderPage();
    const dialog = await openCreate('');
    expect(dialog.getByLabelText('Title').getAttribute('aria-required')).toBe('true');

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    await dialog.findByText('Title is required.');
    expect(dialog.getByLabelText('Title').getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(dialog.getByLabelText('Title'));
  });

  it('GivenThePlanDialog_WhenItIsSubmittedWithoutADate_ThenTheDateIsRequiredAndHasFocus', async () => {
    renderPage();
    const dialog = await openPlan();
    expect(dialog.getByLabelText('Start date').getAttribute('aria-required')).toBe('true');

    fireEvent.change(dialog.getByLabelText('Start date'), { target: { value: '' } });
    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    await dialog.findByText('Pick a start date.');
    expect(document.activeElement).toBe(dialog.getByLabelText('Start date'));
  });
});

/**
 * A date with a part erased or never typed reaches the form as `''`, the value an empty field has. The
 * dialogs set `noValidate`, so the browser no longer refuses it, and the new idea would be saved without
 * the target end the user can see (review finding 1 on #407).
 */
describe('UpgradeBacklogPage unreadable dates', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    planUpgrade.mockReset();
    createUpgrade.mockReset();
    getUpgrades.mockResolvedValue([anIdea()]);
    createUpgrade.mockResolvedValue(anIdea());
    planUpgrade.mockResolvedValue(anIdea({ status: 'PLANNED' }));
  });

  it('GivenAnUnfinishedTargetEnd_WhenAnIdeaIsCreated_ThenNothingIsSentAndTheTargetEndIsRefusedWithFocus', async () => {
    renderPage();
    const dialog = await openCreate();
    enterUnreadableText(dialog.getByLabelText('Target End'));

    fireEvent.submit(dialog.getByRole('button', { name: 'Add Idea' }));

    expect(await dialog.findByText('Finish the date, or clear it.')).toBeDefined();
    expect(document.activeElement).toBe(dialog.getByLabelText('Target End'));
    await settled();
    expect(createUpgrade).not.toHaveBeenCalled();
  });

  it('GivenAnUnfinishedStartDate_WhenThePlanIsSubmitted_ThenNothingIsSentAndTheDateIsRefusedWithFocus', async () => {
    renderPage();
    const dialog = await openPlan();
    enterUnreadableText(dialog.getByLabelText('Start date'));

    fireEvent.submit(dialog.getByRole('button', { name: 'Plan' }));

    expect(await dialog.findByText('Finish the date.')).toBeDefined();
    expect(document.activeElement).toBe(dialog.getByLabelText('Start date'));
    await settled();
    expect(planUpgrade).not.toHaveBeenCalled();
  });

  // Firefox clears an unfinished date, `badInput` and all, as focus leaves a field whose `value` attribute
  // changed since the user began editing, and React rewrites it on every change of a controlled field.
  it('GivenTheStartDate_WhenItIsEdited_ThenItsValueAttributeStaysAsTheDialogOpened', async () => {
    renderPage();
    const dialog = await openPlan();
    const startDate = dialog.getByLabelText<HTMLInputElement>('Start date');
    const opened = startDate.value;

    fireEvent.change(startDate, { target: { value: '' } });

    expect(opened).not.toBe('');
    expect(startDate.getAttribute('value')).toBe(opened);
  });
});

/**
 * The page's headings form an outline: one `h1`, and no level skipped below it, a failed load included.
 * Section titles were plain text, cards were `h3`s straight under the `h1`, and a page that failed to load
 * had no `h1` at all (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('UpgradeBacklogPage headings', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
  });

  it('GivenThePageHasLoaded_WhenItRenders_ThenItsHeadingsFormAnOutlineWithoutGaps', async () => {
    getUpgrades.mockResolvedValue([anIdea()]);

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
