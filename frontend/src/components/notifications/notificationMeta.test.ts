import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { relativeTime } from './notificationMeta';

/**
 * `relativeTime` reads `Date.now()`, so the clock is frozen rather than the assertions loosened —
 * otherwise a test straddling a minute boundary fails once in a while and teaches everyone to re-run.
 */
const NOW = new Date('2026-08-19T12:00:00.000Z');

/** Offsets from {@link NOW}, expressed the way the function's thresholds are. */
const secondsAgo = (seconds: number) => new Date(NOW.getTime() - seconds * 1000).toISOString();
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

  it('GivenATimestampOverAWeekOld_WhenFormatted_ThenItFallsBackToALocaleDate', () => {
    const iso = daysAgo(30);

    expect(relativeTime(iso)).toBe(new Date(iso).toLocaleDateString());
  });

  it('GivenATimestampInTheFuture_WhenFormatted_ThenItReadsJustNow', () => {
    expect(relativeTime(new Date(NOW.getTime() + 60_000).toISOString())).toBe('just now');
  });
});
