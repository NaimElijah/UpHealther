import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { endSession } from './session';

/**
 * A page the browser kept in its back/forward cache comes back with its whole script state, the query
 * cache and the access token included, without loading anything (S5.D1). After a sign-out on a shared
 * machine, Back would show the previous user's data to whoever is at the keyboard. So a page restored
 * from that cache is reloaded, and starts again from the refresh cookie.
 *
 * `window.location` is redefined because jsdom performs neither a navigation nor a reload.
 */
describe('a page restored from the back/forward cache', () => {
  const realLocation = window.location;
  const reload = vi.fn();

  beforeEach(() => {
    reload.mockReset();
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...realLocation, href: '', reload },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  it('GivenTheSessionEnded_WhenThePageIsShownFromTheBackForwardCache_ThenItReloads', () => {
    endSession();

    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('GivenTheSessionEnded_WhenThePageIsShownFromANormalLoad_ThenItDoesNotReload', () => {
    // Every load fires pageshow; only a restore sets `persisted`. Reloading on the first would loop.
    endSession();

    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }));

    expect(reload).not.toHaveBeenCalled();
  });
});
