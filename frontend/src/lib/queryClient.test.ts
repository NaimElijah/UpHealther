import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { endSession } from '../api/session';
import { createQueryClient } from './queryClient';

/**
 * Ending a session navigates away, but a page the browser keeps in its back/forward cache keeps its
 * script state, and the query cache with it (S5.D1). Emptying the cache at the session's end means a
 * restored page has nothing of the previous user's to show, even before it reloads.
 *
 * `window.location` is redefined because jsdom refuses the navigation `endSession` performs.
 */
describe('the application query cache', () => {
  const realLocation = window.location;

  beforeEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...realLocation, href: '' },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  it('GivenCachedServerState_WhenTheSessionEnds_ThenTheCacheIsEmpty', () => {
    const queryClient = createQueryClient();
    queryClient.setQueryData(['upgrades'], [{ id: 'u-1', title: "The previous user's upgrade" }]);

    endSession();

    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });
});
