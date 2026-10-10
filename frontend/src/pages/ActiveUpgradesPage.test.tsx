import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

function anActiveUpgrade(): HealthUpgrade {
  return {
    id: 'upgrade-1',
    userId: 'user-1',
    title: 'Evening walk',
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

  it('GivenTheApiRefusesAPause_WhenItIsPressed_ThenThePageSaysWhyWithTheReference', async () => {
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
