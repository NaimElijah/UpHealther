import type { NotificationCategory } from '../../types';

/** Icon + Tailwind classes per notification category, shared by the toast, dropdown and list. */
export const categoryMeta: Record<NotificationCategory, { icon: string; bg: string; ring: string; text: string }> = {
  INFO: { icon: 'ℹ️', bg: 'bg-blue-50', ring: 'border-blue-200', text: 'text-blue-700' },
  SUCCESS: { icon: '✅', bg: 'bg-green-50', ring: 'border-green-200', text: 'text-green-700' },
  WARNING: { icon: '⚠️', bg: 'bg-red-50', ring: 'border-red-200', text: 'text-red-700' },
  REMINDER: { icon: '⏰', bg: 'bg-orange-50', ring: 'border-orange-200', text: 'text-orange-700' },
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
