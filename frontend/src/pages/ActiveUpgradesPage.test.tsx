import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import ActiveUpgradesPage from './ActiveUpgradesPage';
import type { ActionTarget, HealthUpgrade, UpgradeStatus } from '../types';

const getUpgrades = vi.fn();
const performUpgradeAction = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
}));

function anActiveUpgrade(id = 'upgrade-1', title = 'Evening walk'): HealthUpgrade {
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

function aPausedUpgrade(id = 'upgrade-9', title = 'Morning stretch'): HealthUpgrade {
  return { ...anActiveUpgrade(id, title), status: 'PAUSED' };
}

/** Answers each status from `lists`, the way the API filters by one status at a time (FR-11). */
function answerByStatus(lists: Partial<Record<UpgradeStatus, HealthUpgrade[]>>) {
  getUpgrades.mockImplementation((status: UpgradeStatus) => Promise.resolve(lists[status] ?? []));
}

/** A refusal shaped the way axios delivers one, so `toApiError` decodes it as it would in production. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

/** Whether `node` comes after `anchor` in the document - with cards in order, inside the anchor's card. */
function follows(node: Node, anchor: Node): boolean {
  return (anchor.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
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
        <ActiveUpgradesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * NFR-30 (#96) — a status change that fails says so, with the trace id that finds it.
 *
 * Before, the mutation had no error path at all: a refused pause left the card exactly as it was, and
 * the user could not tell a refusal from a click that never registered.
 */
describe('ActiveUpgradesPage', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    performUpgradeAction.mockReset();
    answerByStatus({ ACTIVE: [anActiveUpgrade()] });
  });

  it('GivenTheApiRefusesAPause_WhenItIsPressed_ThenTheCardSaysWhyWithTheReference', async () => {
    performUpgradeAction.mockRejectedValue(
      apiFailure(409, { status: 409, message: 'Resource was modified concurrently. Please retry.', traceId: 'trace-51' }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That change did not go through.');
    expect(alert.textContent).toContain('Resource was modified concurrently. Please retry.');
    expect(alert.textContent).toContain('trace-51');
  });

  it('GivenSeveralCards_WhenAPauseIsRefused_ThenTheRefusalIsShownWithThePressedCard', async () => {
    // Above the grid it can be scrolled out of view, which looks exactly like a click that did nothing.
    answerByStatus({ ACTIVE: [anActiveUpgrade('upgrade-1', 'Evening walk'), anActiveUpgrade('upgrade-2', 'Cold showers')] });
    performUpgradeAction.mockRejectedValue(
      apiFailure(409, { status: 409, message: 'Resource was modified concurrently. Please retry.', traceId: 'trace-53' }),
    );
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[1]);

    const alert = await screen.findByRole('alert');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(follows(alert, screen.getByText('Cold showers'))).toBe(true);
  });

  it('GivenAChangeInFlight_WhenAnotherCardIsPressed_ThenNothingMoreIsSent', async () => {
    // Overlapping changes would leave only the last one's answer on screen; a second press of the same
    // card would be refused and say so while the card showed the first one accepted.
    answerByStatus({ ACTIVE: [anActiveUpgrade('upgrade-1', 'Evening walk'), anActiveUpgrade('upgrade-2', 'Cold showers')] });
    performUpgradeAction.mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByText('Cold showers');

    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[0]);
    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getAllByRole('button', { name: 'Pause' })[0]);
    fireEvent.click(screen.getAllByRole('button', { name: 'Complete' })[1]);

    await settled();
    expect(performUpgradeAction).toHaveBeenCalledTimes(1);
  });

  it('GivenAChangeWasRefused_WhenItIsTriedAgainAndAccepted_ThenTheMessageGoes', async () => {
    performUpgradeAction
      .mockRejectedValueOnce(apiFailure(409, { status: 409, message: 'Resource was modified concurrently. Please retry.', traceId: 'trace-52' }))
      .mockResolvedValueOnce({ ...anActiveUpgrade(), status: 'PAUSED' });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    await screen.findByRole('alert');

    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });
});

/**
 * FR-12 (#176) — a paused upgrade can be resumed.
 *
 * Pause was offered on every running card, and Resume was on a paused card that no page rendered, so a
 * pause could only be undone by hand-crafting a request. The paused upgrades are listed below the
 * running ones, one section down from where Pause was pressed.
 */
describe('ActiveUpgradesPage, paused upgrades', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    performUpgradeAction.mockReset();
  });

  it('GivenAPausedUpgrade_WhenTheActivePageRenders_ThenItIsListedWithResume', async () => {
    answerByStatus({ ACTIVE: [anActiveUpgrade()], PAUSED: [aPausedUpgrade()] });
    renderPage();

    const paused = await screen.findByRole('region', { name: 'Paused' });

    expect(within(paused).getByText('Morning stretch')).toBeDefined();
    expect(within(paused).getByRole('button', { name: 'Resume' })).toBeDefined();
    expect(within(paused).queryByText('Evening walk')).toBeNull();
  });

  it('GivenOnlyPausedUpgrades_WhenTheActivePageRenders_ThenTheyAreListedRatherThanNoActiveUpgrades', async () => {
    answerByStatus({ PAUSED: [aPausedUpgrade()] });
    renderPage();

    const paused = await screen.findByRole('region', { name: 'Paused' });

    expect(within(paused).getByText('Morning stretch')).toBeDefined();
    expect(screen.queryByText('No active upgrades')).toBeNull();
  });

  it('GivenAPausedUpgrade_WhenResumeIsPressed_ThenItIsActivated', async () => {
    answerByStatus({ PAUSED: [aPausedUpgrade('upgrade-9')] });
    performUpgradeAction.mockResolvedValue({ ...aPausedUpgrade('upgrade-9'), status: 'ACTIVE' });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Resume' }));

    await waitFor(() => expect(performUpgradeAction).toHaveBeenCalledWith('upgrade-9', 'ACTIVE'));
  });

  it('GivenAnActiveUpgrade_WhenItIsPaused_ThenItIsListedUnderPausedWithResume', async () => {
    // The server is modelled rather than stubbed, so the test sees where the upgrade went: before #176
    // a pause took it off every list this page shows.
    let upgrade = anActiveUpgrade();
    getUpgrades.mockImplementation((status: UpgradeStatus) => Promise.resolve(upgrade.status === status ? [upgrade] : []));
    performUpgradeAction.mockImplementation((_id: string, status: ActionTarget) => {
      upgrade = { ...upgrade, status };
      return Promise.resolve(upgrade);
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));

    const paused = await screen.findByRole('region', { name: 'Paused' });
    expect(within(paused).getByText('Evening walk')).toBeDefined();
    expect(within(paused).getByRole('button', { name: 'Resume' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
  });

  it('GivenNoActiveOrPausedUpgrades_WhenTheActivePageRenders_ThenNoActiveUpgradesIsShown', async () => {
    answerByStatus({});
    renderPage();

    expect(await screen.findByText('No active upgrades')).toBeDefined();
    expect(screen.queryByRole('region', { name: 'Paused' })).toBeNull();
  });

  it('GivenThePausedReadIsStillLoading_WhenNothingIsRunning_ThenNoActiveUpgradesIsNotClaimedYet', async () => {
    // Claiming there is nothing here, then listing a paused upgrade a moment later, is the flash the
    // page waits for both reads to avoid.
    getUpgrades.mockImplementation((status: UpgradeStatus) =>
      status === 'PAUSED' ? new Promise(() => {}) : Promise.resolve([]),
    );
    renderPage();
    await waitFor(() => expect(getUpgrades).toHaveBeenCalledWith('PAUSED'));

    await settled();
    expect(screen.queryByText('No active upgrades')).toBeNull();
  });

  it('GivenAPausedUpgrade_WhenItIsResumed_ThenItLeavesThePausedSectionAndRunsAgain', async () => {
    let upgrade = aPausedUpgrade();
    getUpgrades.mockImplementation((status: UpgradeStatus) => Promise.resolve(upgrade.status === status ? [upgrade] : []));
    performUpgradeAction.mockImplementation((_id: string, status: ActionTarget) => {
      upgrade = { ...upgrade, status };
      return Promise.resolve(upgrade);
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Resume' }));

    expect(await screen.findByRole('button', { name: 'Pause' })).toBeDefined();
    expect(screen.queryByRole('region', { name: 'Paused' })).toBeNull();
  });

  it('GivenThePausedReadFails_WhenTheActivePageRenders_ThenTheRunningOnesStillShowWithTheReference', async () => {
    getUpgrades.mockImplementation((status: UpgradeStatus) =>
      status === 'PAUSED'
        ? Promise.reject(apiFailure(500, { status: 500, message: 'An unexpected error occurred', traceId: 'trace-61' }))
        : Promise.resolve([anActiveUpgrade()]),
    );
    renderPage();

    const paused = await screen.findByRole('region', { name: 'Paused' });

    expect(screen.getByText('Evening walk')).toBeDefined();
    const alert = within(paused).getByRole('alert');
    expect(alert.textContent).toContain('Could not load your paused upgrades.');
    expect(alert.textContent).toContain('trace-61');
  });
});
