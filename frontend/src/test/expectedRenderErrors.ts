import { vi } from 'vitest';

/**
 * Keeps a render-time throw that a test makes on purpose out of the test output, so a passing run does
 * not print a stack trace that looks like a failure.
 *
 * Two things report such a throw:
 * - React logs it to `console.error`, from inside the reconciler;
 * - jsdom reports it as uncaught, through an `error` event on `window`.
 *
 * Under Vitest 3, jsdom's report also arrived through `console.error`, so one spy silenced both. Vitest
 * 5 sends it to stderr directly, so the event has to be marked handled as well
 * ([ADR-036](../../../docs/ADRs/ADR-036-the-frontend-builds-on-vite-8-and-tests-on-vitest-5.md)).
 *
 * @returns undoes both, for an `afterEach` or a `finally`
 */
export const silenceExpectedRenderErrors = (): (() => void) => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  const markHandled = (event: ErrorEvent) => event.preventDefault();
  window.addEventListener('error', markHandled);

  return () => {
    window.removeEventListener('error', markHandled);
    consoleError.mockRestore();
  };
};
