import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { relativeTime } from './notificationMeta';

/**
 * `relativeTime` reads `Date.now()`, so the clock is frozen rather than the assertions loosened —
 * otherwise a test straddling a minute boundary fails once in a while and teaches everyone to re-run.
 */
const NOW = new Date('2026-08-19T12:00:00.000Z');

/**
 * An instant as the API writes a notification's `createdAt`: a `LocalDateTime` in the server's zone,
 * which is UTC (ADR-020), with no offset on it (`docs/api.md`, `wireFixtures.ts`). `toISOString()` alone
 * ends in `Z`, a shape the API never sends, and a fixture in that shape agreed with a function that reads
 * the wire's string as local time: the suite runs in Los Angeles, where that put every notification
 * seven hours later than it was raised.
 */
const asSent = (instant: Date) => instant.toISOString().slice(0, 19);

/** Offsets from {@link NOW}, expressed the way the function's thresholds are, in the wire's shape. */
const secondsAgo = (seconds: number) => asSent(new Date(NOW.getTime() - seconds * 1000));
const minutesAgo = (minutes: number) => secondsAgo(minutes * 60);
const hoursAgo = (hours: number) => minutesAgo(hours * 60);
const daysAgo = (days: number) => hoursAgo(days * 24);

describe('relativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('GivenATimestampUnderAMinuteOld_WhenFormatted_ThenItReadsJustNow', () => {
    expect(relativeTime(secondsAgo(30))).toBe('just now');
  });

  it('GivenATimestampOfExactlyOneMinute_WhenFormatted_ThenItReadsInMinutes', () => {
    expect(relativeTime(minutesAgo(1))).toBe('1m ago');
  });

  it('GivenATimestampOfHours_WhenFormatted_ThenItReadsInHours', () => {
    expect(relativeTime(hoursAgo(3))).toBe('3h ago');
  });

  it('GivenATimestampOfDays_WhenFormatted_ThenItReadsInDays', () => {
    expect(relativeTime(daysAgo(2))).toBe('2d ago');
  });

  it('GivenATimestampOverAWeekOld_WhenFormatted_ThenItFallsBackToTheLocalDateOfThatInstant', () => {
    // 03:00 UTC, which is still the evening before in Los Angeles: the date shown is the user's.
    const instant = new Date(NOW.getTime() - (30 * 24 + 9) * 3600 * 1000);

    expect(relativeTime(asSent(instant))).toBe(instant.toLocaleDateString());
  });

  it('GivenATimestampInTheFuture_WhenFormatted_ThenItReadsJustNow', () => {
    expect(relativeTime(asSent(new Date(NOW.getTime() + 60_000)))).toBe('just now');
  });

  // ISO_LOCAL_DATE_TIME, which the API's LocalDateTime serialiser uses, writes a fraction only when there
  // is one, with its trailing zeros dropped. It always writes the seconds, so `2026-08-19T09:00` is not
  // the wire's shape.
  it.each(['2026-08-19T09:00:00', '2026-08-19T09:00:00.123456', '2026-08-19T09:00:00.12'])(
    'GivenTheWiresShape %j_WhenFormatted_ThenItReadsItsAge',
    (sent) => {
      expect(relativeTime(sent)).toBe('3h ago');
    },
  );

  // A value outside the contract is a bug worth a loud failure, as in `parseLocalDate`: formatted, it
  // would read "Invalid Date" in every row, with nothing to say why.
  it.each(['2026-08-19T09:00:00Z', '2026-08-19T09:00:00+02:00', 'not a date'])(
    'GivenAValueOutsideTheWiresShape %j_WhenFormatted_ThenItThrows',
    (sent) => {
      expect(() => relativeTime(sent)).toThrow(RangeError);
    },
  );

  // These match the wire's shape, and `Date` would roll each over into the next day rather than refuse it.
  it.each(['2026-02-30T09:00:00', '2026-08-19T24:00:00'])(
    'GivenAMomentThatDoesNotExist %j_WhenFormatted_ThenItThrows',
    (sent) => {
      expect(() => relativeTime(sent)).toThrow(RangeError);
    },
  );
});
