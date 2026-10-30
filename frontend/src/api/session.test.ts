import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
// The `pageshow` listener registers when the module loads, so the first block needs only the import.
import { endSession, onSessionEnd } from './session';

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

  it('GivenAPageRestoredFromTheBackForwardCache_WhenItIsShown_ThenItReloads', () => {
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('GivenANormalLoad_WhenThePageIsShown_ThenItDoesNotReload', () => {
    // Every load fires pageshow; only a restore sets `persisted`. Reloading on the first would loop.
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }));

    expect(reload).not.toHaveBeenCalled();
  });
});

describe('ending a session', () => {
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

  it('GivenAListenerThatThrows_WhenTheSessionEnds_ThenTheErrorPropagatesAndTheTabStillGoesToSignIn', () => {
    // A failure while forgetting the session must not leave the tab on the page that showed it (S5.C6).
    const failure = new Error('the listener failed');
    const unregister = onSessionEnd(() => {
      throw failure;
    });
    try {
      expect(() => endSession()).toThrow(failure);
      expect(window.location.href).toBe('/login');
    } finally {
      unregister();
    }
  });
});
