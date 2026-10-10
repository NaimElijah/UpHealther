import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import PlannedUpgradesPage from './PlannedUpgradesPage';
import type { HealthUpgrade } from '../types';

const getUpgrades = vi.fn();
const performUpgradeAction = vi.fn();

vi.mock('../api/upgrades', () => ({
  getUpgrades: (...a: unknown[]) => getUpgrades(...a),
  performUpgradeAction: (...a: unknown[]) => performUpgradeAction(...a),
}));

function aPlannedHardUpgrade(): HealthUpgrade {
  return {
    id: 'upgrade-1',
    userId: 'user-1',
    title: 'Run a half marathon',
    type: 'GOAL',
    status: 'PLANNED',
    difficulty: 'HARD',
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
      apiFailure(422, { status: 422, message: 'Cannot run more than 3 HARD upgrades simultaneously', traceId: 'trace-61' }),
    );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Activate' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('That change did not go through.');
    expect(alert.textContent).toContain('Cannot run more than 3 HARD upgrades simultaneously');
    expect(alert.textContent).toContain('trace-61');
    // With the card, below its title, rather than above the list where a long timeline hides it.
    expect((screen.getByText('Run a half marathon').compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0).toBe(true);
  });
});
