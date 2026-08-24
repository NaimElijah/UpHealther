import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import DailyCheckinPage from './DailyCheckinPage';
import type { HealthUpgrade } from '../types';

const getUpgrades = vi.fn();
const createProgress = vi.fn();

vi.mock('../api/upgrades', () => ({ getUpgrades: (...a: unknown[]) => getUpgrades(...a) }));
vi.mock('../api/progress', () => ({ createProgress: (...a: unknown[]) => createProgress(...a) }));

function anUpgrade(overrides: Partial<HealthUpgrade> = {}): HealthUpgrade {
  return {
    id: 'upgrade-1',
    userId: 'user-1',
    areaId: null,
    title: 'Cold showers',
    description: null,
    type: 'HABIT',
    status: 'ACTIVE',
    difficulty: 'MEDIUM',
    plannedStartDate: null,
    actualStartDate: null,
    targetEndDate: null,
    motivation: null,
    successCriteria: null,
    overdue: false,
    version: 0,
    trackingConfig: null,
    createdAt: '2026-03-15T09:00:00',
    updatedAt: '2026-03-15T09:00:00',
    ...overrides,
  } as HealthUpgrade;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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
 * <strong>One assertion below records behaviour that does not match its own documentation.</strong> The
 * component's doc comment says an untouched upgrade is "left unlogged rather than recorded as missed",
 * but `handleSubmit` maps over every active upgrade and posts an entry for each. Because at most one
 * entry may exist per upgrade per day (BR-6), an untouched upgrade is therefore logged as a failure
 * that cannot be corrected later the same day. Asserted as it behaves, not as the comment claims;
 * which of the two is right is a product decision rather than a test's to make.
 */
describe('DailyCheckinPage', () => {
  beforeEach(() => {
    getUpgrades.mockReset();
    createProgress.mockReset();
    createProgress.mockResolvedValue({});
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
    // Contradicts the component's own doc comment — see the note on this describe block. Recorded so
    // the disagreement is visible rather than discovered again from a support question.
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
    getUpgrades.mockResolvedValue([anUpgrade({
      id: 'a',
      trackingConfig: { trackingType: 'NUMERIC', targetUnit: 'steps' } as HealthUpgrade['trackingConfig'],
    })]);
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
    getUpgrades.mockResolvedValue([anUpgrade({
      id: 'a',
      trackingConfig: { trackingType: 'NUMERIC', targetUnit: 'steps' } as HealthUpgrade['trackingConfig'],
    })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('steps')).toBeDefined());

    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '5' } });
    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '' } });
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0].numericValue).toBeUndefined();
  });

  it('GivenARatingUpgrade_WhenAScoreIsChosen_ThenTheEntryCarriesIt', async () => {
    getUpgrades.mockResolvedValue([anUpgrade({
      id: 'a',
      trackingConfig: { trackingType: 'RATING' } as HealthUpgrade['trackingConfig'],
    })]);
    renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: '4' })).toBeDefined());

    fireEvent.click(screen.getByRole('button', { name: '4' }));
    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(createProgress).toHaveBeenCalled());
    expect(createProgress.mock.calls[0][0]).toMatchObject({ rating: 4 });
  });

  it('GivenTheCheckinIsSubmitted_WhenEveryEntrySucceeds_ThenTheFormIsReplacedByAConfirmation', async () => {
    getUpgrades.mockResolvedValue([anUpgrade({ id: 'a' })]);
    renderPage();
    await waitFor(() => expect(screen.getByText('Cold showers')).toBeDefined());

    fireEvent.submit(screen.getByRole('button', { name: /submit check-in/i }));

    await waitFor(() => expect(screen.getByText('Check-in Complete!')).toBeDefined());
    expect(screen.queryByRole('button', { name: /submit check-in/i })).toBeNull();
  });
});
