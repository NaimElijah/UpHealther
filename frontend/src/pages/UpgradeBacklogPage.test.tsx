import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import UpgradeBacklogPage from './UpgradeBacklogPage';
import type { HealthUpgrade } from '../types';

const getUpgrades = vi.fn();
const planUpgrade = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  planUpgrade: (...a: unknown[]) => planUpgrade(...a),
  createUpgrade: vi.fn(),
}));

vi.mock('../api/healthAreas', () => ({
  getHealthAreas: () => Promise.resolve([]),
}));

const UPGRADE_ID = 'upgrade-1';

function anIdea(values: Partial<HealthUpgrade> = {}): HealthUpgrade {
  return {
    id: UPGRADE_ID,
    userId: 'user-1',
    title: 'Cold showers',
    type: 'HABIT',
    status: 'IDEA',
    difficulty: 'MEDIUM',
    createdAt: '2026-03-01T09:00:00',
    version: 0,
    ...values,
  };
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

/** Presses Plan on the only idea listed, and returns queries scoped to the dialog it opens. */
async function openPlan() {
  await screen.findByText('Cold showers');
  fireEvent.click(screen.getByRole('button', { name: 'Plan' }));
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

  it('GivenAPlanInFlight_WhenTheDialogIsDismissed_ThenItStaysOpenUntilTheAnswerArrives', async () => {
    // One mutation serves every idea. Dismissed mid-flight, the dialog would let another idea's open
    // before this answer lands; the answer would then close that one, or put this refusal in it.
    let answer: (planned: HealthUpgrade) => void = () => {};
    planUpgrade.mockImplementation(() => new Promise<HealthUpgrade>((resolve) => { answer = resolve; }));
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
