import type { NotificationCategory } from '../../types';

/** A category's icon and the token classes its toast and row are drawn with. */
interface CategoryMeta {
  icon: string;
  bg: string;
  border: string;
  text: string;
}

/**
 * Icon + token classes per notification category, shared by the toast, dropdown and list.
 *
 * Each category owns a hue that means only that category. WARNING is amber rather than red, so a
 * warning cannot be mistaken for a failure; REMINDER is violet rather than orange, so it cannot be
 * mistaken for a streak celebration; INFO is cyan rather than the brand blue the sidebar's active
 * state already uses.
 */
const categoryMeta: Record<NotificationCategory, CategoryMeta> = {
  INFO: { icon: 'ℹ️', bg: 'bg-info-soft', border: 'border-info-line', text: 'text-info-fg' },
  SUCCESS: { icon: '✅', bg: 'bg-success-soft', border: 'border-success-line', text: 'text-success-fg' },
  WARNING: { icon: '⚠️', bg: 'bg-warning-soft', border: 'border-warning-line', text: 'text-warning-fg' },
  REMINDER: { icon: '⏰', bg: 'bg-reminder-soft', border: 'border-reminder-line', text: 'text-reminder-fg' },
};

/**
 * The icon and token classes for a category, INFO's for one this build does not know.
 *
 * The fallback is needed although the map is total: the category arrives as unvalidated JSON, so a tab
 * opened before a deploy that adds one receives a value outside the union, and reading its classes
 * without a fallback threw during render, which the error boundary turned into a blank app.
 *
 * @param category the value from the API, typed as `NotificationCategory` but not validated as one
 */
export function categoryStyle(category: NotificationCategory): CategoryMeta {
  return categoryMeta[category] ?? categoryMeta.INFO;
}

/**
 * `YYYY-MM-DDTHH:mm:ss[.fraction]` as `ISO_LOCAL_DATE_TIME` writes it, which is what the API's
 * `LocalDateTime` serialiser uses: the year as `ISO_LOCAL_DATE` writes it, always the seconds, a fraction
 * of up to nine digits only when it is non-zero, and no offset.
 */
const LOCAL_DATE_TIME = /^([+-]?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/;

/** The fraction digits a `Date` can hold, which counts milliseconds; finer ones are dropped. */
const MILLISECOND_DIGITS = 3;

/**
 * Reads the API's offset-less date-time as the UTC instant the server meant (ADR-020).
 *
 * Built from the fields rather than through `new Date`, which takes a date-time without an offset as
 * the browser's local time, and rolls an impossible day such as `2026-02-30` over into the next month.
 */
const parseUtcLocalDateTime = (iso: string): Date => {
  const match = LOCAL_DATE_TIME.exec(iso);
  if (!match) throw new RangeError(`Expected a date-time as YYYY-MM-DDTHH:mm:ss[.fraction], got "${iso}"`);
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  const millisecond = Number((match[7] ?? '').slice(0, MILLISECOND_DIGITS).padEnd(MILLISECOND_DIGITS, '0'));
  const parsed = new Date(0);
  parsed.setUTCFullYear(year, month - 1, day);
  parsed.setUTCHours(hour, minute, second, millisecond);
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day ||
    parsed.getUTCHours() !== hour ||
    parsed.getUTCMinutes() !== minute ||
    parsed.getUTCSeconds() !== second
  ) {
    throw new RangeError(`"${iso}" is not a calendar date-time`);
  }
  return parsed;
};

/**
 * Formats a notification's `createdAt` as "just now", "5m ago", "3h ago" or "2d ago", falling back to
 * the user's locale date beyond a week.
 *
 * Read as UTC, because the server writes its date-times in UTC with no offset on them (ADR-020); read
 * as the browser's local time, every age shifted by the user's own offset.
 *
 * Coarse on purpose: a notification list is scanned, not read closely, and a rounded figure is quicker
 * to take in than an exact one.
 *
 * @param iso an offset-less ISO local date-time exactly as the API sends it, such as
 *        `2026-01-14T09:13:02` or `2026-01-14T09:13:02.123456`
 * @returns the age of that instant, or its local date once it is a week old or more
 * @throws RangeError when `iso` is not an ISO local date-time with seconds — one carrying an offset or
 *         a `Z` included — or names a moment that does not exist (such as `2026-02-30T09:00:00`), rather
 *         than rendering "Invalid Date" or a rolled-over day
 */
export const relativeTime = (iso: string): string => {
  const instant = parseUtcLocalDateTime(iso);
  const diffMs = Date.now() - instant.getTime();
  const sec = Math.round(diffMs / 1000);
  if (sec < 60) return 'just now';
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.round(hr / 24);
  if (days < 7) return `${days}d ago`;
  return instant.toLocaleDateString();
};
