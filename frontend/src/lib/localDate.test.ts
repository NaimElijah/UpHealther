import { describe, it, expect, afterEach, vi } from 'vitest';
import { parseLocalDate, todayLocal } from './localDate';

/**
 * FR-18 and FR-19 (#95) — a day is the user's day, not UTC's.
 *
 * The suite runs in America/Los_Angeles (see `vitest.config.ts`), so an instant late in the local
 * evening is already the next day in UTC. That is one of the two moments the old `toISOString()` date
 * got wrong. The other, just after midnight east of UTC, cannot happen in that zone, so its test moves
 * the process to another one for its own duration.
 */
describe('localDate', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
  });

  it('GivenLateEveningWestOfUtc_WhenTodayLocal_ThenItIsTheLocalDay', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 22:00 on 11 March in Los Angeles, and already 12 March in UTC.
    vi.setSystemTime(new Date('2026-03-12T05:00:00Z'));

    expect(todayLocal()).toBe('2026-03-11');
  });

  it('GivenJustAfterMidnightEastOfUtc_WhenTodayLocal_ThenItIsTheLocalDay', () => {
    // 00:30 on 1 October in Jerusalem, and still 30 September in UTC. Node re-reads TZ when it is
    // assigned; unstubAllEnvs puts the suite's zone back before the next test.
    vi.stubEnv('TZ', 'Asia/Jerusalem');
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-09-30T21:30:00Z'));

    expect(todayLocal()).toBe('2026-10-01');
  });

  it('GivenADateOnlyString_WhenParsed_ThenItIsThatDayLocally', () => {
    const parsed = parseLocalDate('2026-03-12');

    expect([parsed.getFullYear(), parsed.getMonth(), parsed.getDate(), parsed.getHours()]).toEqual([2026, 2, 12, 0]);
  });

  it('GivenAYearBelowOneHundred_WhenParsed_ThenItIsThatYearAndNotTheNineteenHundreds', () => {
    // A legal LocalDate the editable Log Progress date field can store. `new Date(50, 2, 12)` is 1950.
    const parsed = parseLocalDate('0050-03-12');

    expect([parsed.getFullYear(), parsed.getMonth(), parsed.getDate()]).toEqual([50, 2, 12]);
  });

  it('GivenAYearPast9999AsTheApiWritesIt_WhenParsed_ThenItIsThatYear', () => {
    // ISO_LOCAL_DATE, which the API's LocalDate serialiser uses, signs a year of more than four digits.
    const parsed = parseLocalDate('+10000-01-01');

    expect([parsed.getFullYear(), parsed.getMonth(), parsed.getDate()]).toEqual([10000, 0, 1]);
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
