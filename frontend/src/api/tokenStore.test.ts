import { describe, it, expect, beforeEach } from 'vitest';
import {
  clearAccessToken,
  getAccessToken,
  hasUsableAccessToken,
  setAccessToken,
} from './tokenStore';

/**
 * The access token's hiding place.
 *
 * Small enough to look trivial, and worth pinning anyway: the one property that matters is negative.
 * Nothing here may reach `localStorage` or `sessionStorage`, because anything injected into the page
 * can read those and copy what it finds somewhere that outlives the tab. That is asserted directly
 * below, since it is exactly the kind of thing a later convenience ("survive a reload!") would undo
 * without anybody noticing.
 */
describe('the access token store', () => {
  beforeEach(() => {
    clearAccessToken();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('GivenAStoredToken_WhenItIsRead_ThenItIsTheOneThatWasStored', () => {
    setAccessToken('issued.jwt.token', '2026-09-20T10:15:00Z');

    expect(getAccessToken()).toBe('issued.jwt.token');
  });

  it('GivenNoTokenHasBeenStored_WhenItIsRead_ThenThereIsNone', () => {
    // The state every page load starts in: the token died with the previous tab.
    expect(getAccessToken()).toBeNull();
    expect(hasUsableAccessToken()).toBe(false);
  });

  it('GivenAStoredToken_WhenWebStorageIsInspected_ThenNothingWasWrittenToIt', () => {
    setAccessToken('issued.jwt.token', '2026-09-20T10:15:00Z');

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('GivenAStoredToken_WhenItIsCleared_ThenThereIsNothingLeftToSend', () => {
    setAccessToken('issued.jwt.token', '2026-09-20T10:15:00Z');

    clearAccessToken();

    expect(getAccessToken()).toBeNull();
    expect(hasUsableAccessToken()).toBe(false);
  });

  it('GivenATokenWithTimeLeft_WhenItIsJudged_ThenItIsWorthSending', () => {
    const expiry = new Date('2026-09-20T10:15:00Z');
    setAccessToken('issued.jwt.token', expiry.toISOString());

    expect(hasUsableAccessToken(expiry.getTime() - 120_000)).toBe(true);
  });

  it('GivenATokenAboutToLapse_WhenItIsJudged_ThenItIsAlreadyTreatedAsSpent', () => {
    // A token with two seconds left will lapse in transit, so sending it buys a wasted round trip and
    // a retry. Renewing a moment early costs one refresh and the user sees nothing.
    const expiry = new Date('2026-09-20T10:15:00Z');
    setAccessToken('issued.jwt.token', expiry.toISOString());

    expect(hasUsableAccessToken(expiry.getTime() - 2_000)).toBe(false);
  });

  it('GivenATokenWithAnUnreadableExpiry_WhenItIsJudged_ThenItIsStillWorthSending', () => {
    // Being refused is recoverable — the interceptor renews and retries. Refusing to send a token the
    // server issued, because its expiry did not parse, is not.
    setAccessToken('issued.jwt.token', 'not-a-date');

    expect(hasUsableAccessToken()).toBe(true);
  });
});
