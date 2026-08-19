import type { NotificationCategory } from '../../types';

/**
 * Icon + token classes per notification category, shared by the toast, dropdown and list.
 *
 * Each category owns a hue that means only that category. WARNING is amber rather than red, so a
 * warning cannot be mistaken for a failure; REMINDER is violet rather than orange, so it cannot be
 * mistaken for a streak celebration; INFO is cyan rather than the brand blue the sidebar's active
 * state already uses.
 */
export const categoryMeta: Record<NotificationCategory, { icon: string; bg: string; border: string; text: string }> = {
  INFO: { icon: 'ℹ️', bg: 'bg-info-soft', border: 'border-info-line', text: 'text-info-fg' },
  SUCCESS: { icon: '✅', bg: 'bg-success-soft', border: 'border-success-line', text: 'text-success-fg' },
  WARNING: { icon: '⚠️', bg: 'bg-warning-soft', border: 'border-warning-line', text: 'text-warning-fg' },
  REMINDER: { icon: '⏰', bg: 'bg-reminder-soft', border: 'border-reminder-line', text: 'text-reminder-fg' },
};

/**
 * Formats an ISO timestamp as "just now", "5m ago", "3h ago" or "2d ago", falling back to a locale date
 * beyond a week.
 *
 * Coarse on purpose: a notification list is scanned, not read closely, and a rounded figure is quicker
 * to take in than an exact one.
 */
export const relativeTime = (iso: string): string => {
  const diffMs = Date.now() - new Date(iso).getTime();
  const sec = Math.round(diffMs / 1000);
  if (sec < 60) return 'just now';
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.round(hr / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
};
