import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ErrorBoundary from './ErrorBoundary';

/**
 * The one construct that stops a render-time throw becoming a blank page.
 *
 * Nothing in the application can reach this by clicking, which is exactly why it needs a test: it is
 * invisible until the day it matters, and the failure mode if it is wired wrong — the tree unmounts to
 * white — is indistinguishable from the bug it was meant to contain.
 *
 * React logs a caught error to `console.error` itself, from inside the reconciler. That is React's
 * output, not this application's, and it is silenced here so a passing run does not print a stack
 * trace that looks like a failure.
 */
describe('ErrorBoundary', () => {
  const Throws = () => {
    throw new Error('render blew up');
  };

  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('GivenAChildThatRendersNormally_WhenTheBoundaryRenders_ThenItIsOutOfTheWay', () => {
    render(<ErrorBoundary><p>the page</p></ErrorBoundary>);

    expect(screen.getByText('the page')).toBeDefined();
  });

  it('GivenAChildThatThrows_WhenItRenders_ThenTheUserSeesAMessageRatherThanABlankPage', () => {
    render(<ErrorBoundary><Throws /></ErrorBoundary>);

    expect(screen.getByRole('alert').textContent).toContain('This page stopped working.');
  });

  it('GivenAChildThatThrows_WhenItRenders_ThenTheUserIsToldTheirDataIsSafe', () => {
    // The first question somebody asks when a page breaks mid-edit, and the answer is yes: nothing
    // here writes, so a render error cannot have lost anything.
    render(<ErrorBoundary><Throws /></ErrorBoundary>);

    expect(screen.getByRole('alert').textContent).toContain('Nothing you have saved is affected.');
  });

  it('GivenACaughtError_WhenTheFallbackRenders_ThenItOffersARecovery', () => {
    const reload = vi.fn();
    const realLocation = window.location;
    Object.defineProperty(window, 'location', { value: { ...realLocation, reload }, writable: true });

    try {
      render(<ErrorBoundary><Throws /></ErrorBoundary>);
      fireEvent.click(screen.getByRole('button', { name: 'Reload the page' }));

      // A reload, not a re-render: whatever was inconsistent about the props or the cache still is,
      // so clearing the error state would usually throw again immediately.
      expect(reload).toHaveBeenCalledOnce();
    } finally {
      Object.defineProperty(window, 'location', { value: realLocation, writable: true });
    }
  });
});
