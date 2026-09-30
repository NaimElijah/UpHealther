/**
 * Calendar dates as the user lives them, in the browser's own time zone.
 *
 * The API speaks in `YYYY-MM-DD` — a day with no time and no zone (a Java `LocalDate`). JavaScript has
 * no such type, and its two obvious shortcuts both go through UTC: `toISOString()` gives the UTC date,
 * which is tomorrow in the evening west of UTC and yesterday after midnight east of it; and
 * `new Date('YYYY-MM-DD')` is UTC midnight, which west of UTC is displayed as the day before (#95).
 * Everything here reads and builds the date from local fields instead.
 */

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Left-pads a month or day to the two digits the `YYYY-MM-DD` shape requires. */
const twoDigits = (n: number): string => String(n).padStart(2, '0');

/**
 * Today in the user's time zone, as `YYYY-MM-DD` — the shape the progress and reflection APIs expect.
 *
 * Read afresh on every call, so a page left open past midnight gets the new day.
 *
 * @returns the local calendar date of the current instant
 */
export function todayLocal(): string {
  const now = new Date();
  return `${now.getFullYear()}-${twoDigits(now.getMonth() + 1)}-${twoDigits(now.getDate())}`;
}

/**
 * Parses a `YYYY-MM-DD` date from the API as local midnight of that day, so formatting it shows the
 * same day in every time zone.
 *
 * @param isoDate a date-only string exactly as the API sends it
 * @returns a `Date` at 00:00 local time on that day
 * @throws RangeError when `isoDate` is not `YYYY-MM-DD`, or names a day that does not exist (such as
 *         `2026-02-30`); failing here is louder than rendering "Invalid Date" or a rolled-over day
 */
export function parseLocalDate(isoDate: string): Date {
  const match = DATE_ONLY.exec(isoDate);
  if (!match) throw new RangeError(`Expected a date as YYYY-MM-DD, got "${isoDate}"`);
  const [year, month, day] = match.slice(1).map(Number);
  const parsed = new Date(year, month - 1, day);
  // `Date` rolls an out-of-range month or day over into the next one rather than refusing it, and
  // reads a year below 100 as 19xx.
  if (parsed.getFullYear() !== year || parsed.getMonth() !== month - 1 || parsed.getDate() !== day) {
    throw new RangeError(`"${isoDate}" is not a calendar date`);
  }
  return parsed;
}
