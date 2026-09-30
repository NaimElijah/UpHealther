import { describe, expect, it } from 'vitest';

/**
 * The date tests are written for one zone, set in `vitest.config.ts`. Should a runner ever start the
 * workers without it, they would run in the machine's zone and pass or fail by where they ran — the
 * way #95 went unseen. This makes that loud instead.
 */
describe('the suite time zone', () => {
  it('GivenTheSuiteConfiguration_WhenATestRuns_ThenItRunsInTheZoneTheDateTestsAssume', () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/Los_Angeles');
  });
});
