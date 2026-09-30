import { describe, it, expect, afterEach, vi } from 'vitest';
import { parseLocalDate, todayLocal } from './localDate';

/**
 * FR-18 and FR-19 (#95) — a day is the user's day, not UTC's.
 *
 * The suite runs in America/Los_Angeles (see `vitest.config.ts`), so an instant late in the local
 * evening is already the next day in UTC. That is the moment the old `toISOString()` date got wrong.
 */
describe('localDate', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('GivenLateEveningWestOfUtc_WhenTodayLocal_ThenItIsTheLocalDay', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 22:00 on 11 March in Los Angeles, and already 12 March in UTC.
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));

    expect(todayLocal()).toBe('2026-03-11');
  });

  it('GivenJustAfterMidnightWestOfUtc_WhenTodayLocal_ThenItIsTheNewLocalDay', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 0, 5, 0, 1));

    expect(todayLocal()).toBe('2026-01-05');
  });

  it('GivenADateOnlyString_WhenParsed_ThenItIsThatDayLocally', () => {
    const parsed = parseLocalDate('2026-03-12');

    expect([parsed.getFullYear(), parsed.getMonth(), parsed.getDate(), parsed.getHours()]).toEqual([2026, 2, 12, 0]);
  });

  it('GivenAParsedDate_WhenFormattedBackToTheApiShape_ThenItRoundTrips', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(parseLocalDate('2026-12-31'));

    expect(todayLocal()).toBe('2026-12-31');
  });

  it.each(['', '2026-3-12', '12/03/2026', '2026-03-12T00:00:00', '2026-02-30', '2026-13-01'])(
    'GivenTheMalformedDate %j_WhenParsed_ThenItThrows',
    (input) => {
      expect(() => parseLocalDate(input)).toThrow(RangeError);
    },
  );
});
