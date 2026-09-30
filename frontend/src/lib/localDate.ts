/**
 * Calendar dates as the user lives them, in the browser's own time zone.
 *
 * The API speaks in `YYYY-MM-DD` — a day with no time and no zone (a Java `LocalDate`). JavaScript has
 * no such type, and its two obvious shortcuts both go through UTC: `toISOString()` gives the UTC date,
 * which is tomorrow in the evening west of UTC and yesterday after midnight east of it; and
 * `new Date('YYYY-MM-DD')` is UTC midnight, which west of UTC is displayed as the day before (#95).
 * Everything here reads and builds the date from local fields instead.
 */

/**
 * `YYYY-MM-DD` as `ISO_LOCAL_DATE` writes it, which is what the API's `LocalDate` serialiser uses: at
 * least four year digits, and a sign once there are more than four (`+10000-01-01`).
 */
const DATE_ONLY = /^([+-]?\d{4,})-(\d{2})-(\d{2})$/;

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
 * Parses a date from the API as local midnight of that day, so formatting it shows the same day in
 * every time zone.
 *
 * Every date the API can legally send parses, whatever its year: the date fields are editable and
 * nothing bounds the year, and this runs during render, where a throw replaces the whole app with the
 * error boundary. What still throws is a value outside the contract, which is a bug worth that noise.
 *
 * @param isoDate a date-only string exactly as the API sends it
 * @returns a `Date` at 00:00 local time on that day
 * @throws RangeError when `isoDate` is not an ISO local date, or names a day that does not exist (such
 *         as `2026-02-30`) — rather than rendering "Invalid Date" or a rolled-over day
 */
export function parseLocalDate(isoDate: string): Date {
  const match = DATE_ONLY.exec(isoDate);
  if (!match) throw new RangeError(`Expected a date as YYYY-MM-DD, got "${isoDate}"`);
  const [year, month, day] = match.slice(1).map(Number);
  // `setFullYear` rather than the constructor, which reads a year below 100 as 19xx.
  const parsed = new Date(0);
  parsed.setFullYear(year, month - 1, day);
  parsed.setHours(0, 0, 0, 0);
  // `Date` rolls an out-of-range month or day over into the next one rather than refusing it.
  if (parsed.getFullYear() !== year || parsed.getMonth() !== month - 1 || parsed.getDate() !== day) {
    throw new RangeError(`"${isoDate}" is not a calendar date`);
  }
  return parsed;
}
