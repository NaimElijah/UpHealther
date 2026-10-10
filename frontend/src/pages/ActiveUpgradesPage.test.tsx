import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import ActiveUpgradesPage from './ActiveUpgradesPage';
import type { HealthUpgrade } from '../types';

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
    getUpgrades.mockResolvedValue([anActiveUpgrade()]);
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
    getUpgrades.mockResolvedValue([anActiveUpgrade('upgrade-1', 'Evening walk'), anActiveUpgrade('upgrade-2', 'Cold showers')]);
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
    getUpgrades.mockResolvedValue([anActiveUpgrade('upgrade-1', 'Evening walk'), anActiveUpgrade('upgrade-2', 'Cold showers')]);
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
