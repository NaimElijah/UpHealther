import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ErrorState from './ErrorState';

/**
 * What a page shows instead of the data it could not load.
 *
 * The assertion that matters is the trace id reaching the screen. The backend produces one on every
 * response specifically so a user can quote it, and before this component the seven pages that
 * rendered a failure discarded it — leaving a bug report with nothing in it that could find the
 * request in the log.
 */
describe('ErrorState', () => {
  it('GivenAFailureCarryingATraceId_WhenItRenders_ThenTheIdIsOnTheScreen', () => {
    render(<ErrorState title="Could not load your upgrades." error={{
      message: 'Internal server error',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    }} />);

    expect(screen.getByText('4bf92f3577b34da6a3ce929d0e0e4736')).toBeDefined();
  });

  it('GivenAFailureWithNoTraceId_WhenItRenders_ThenNoReferenceIsOffered', () => {
    // A network failure never reached the server, so there is no id — and offering one would send
    // somebody searching a log for something that was never written to it.
    render(<ErrorState title="Could not load your upgrades." error={{ message: 'Could not reach the server.' }} />);

    expect(screen.queryByText(/reference/i)).toBeNull();
  });

  it('GivenAFailure_WhenItRenders_ThenItAnnouncesItselfToAssistiveTechnology', () => {
    render(<ErrorState title="Could not load your dashboard." error={{ message: 'Internal server error' }} />);

    expect(screen.getByRole('alert').textContent).toContain('Could not load your dashboard.');
  });

  it('GivenNoFailureDetail_WhenItRenders_ThenTheTitleStillExplainsWhatHappened', () => {
    // UpgradeDetailsPage renders this for "the upgrade came back empty", where there is no error.
    render(<ErrorState title="Could not load this upgrade." />);

    expect(screen.getByText('Could not load this upgrade.')).toBeDefined();
  });
});
