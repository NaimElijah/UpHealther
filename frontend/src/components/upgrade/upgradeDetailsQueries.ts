import { queryOptions, type UseQueryResult } from '@tanstack/react-query';
import { getProgressByUpgrade, getStreak } from '../../api/progress';
import { getReflectionsByUpgrade } from '../../api/reflections';
import { getReminders } from '../../api/reminders';

/**
 * The reads of an upgrade page's sections, each defined once: its key and how it is fetched.
 *
 * The page reads all four, beside the upgrade's read, as it mounts, and hands each section its result
 * as a {@link SectionRead}. A section renders only once the upgrade has loaded, so a read started
 * there would wait for the upgrade's. A section takes its key from here when a write invalidates it,
 * so the key is spelled once.
 *
 * Every factory takes the id the route gave, which may be missing, and is enabled only when it is not.
 */

/** An upgrade's progress history, newest first, as `GET /api/upgrades/{id}/progress` returns it. */
export const progressQuery = (upgradeId: string | undefined) =>
  queryOptions({
    queryKey: ['progress', upgradeId],
    queryFn: () => getProgressByUpgrade(upgradeId!),
    enabled: !!upgradeId,
  });

/** An upgrade's current and longest streak. */
export const streakQuery = (upgradeId: string | undefined) =>
  queryOptions({
    queryKey: ['streak', upgradeId],
    queryFn: () => getStreak(upgradeId!),
    enabled: !!upgradeId,
  });

/** An upgrade's reflections. */
export const reflectionsQuery = (upgradeId: string | undefined) =>
  queryOptions({
    queryKey: ['reflections', upgradeId],
    queryFn: () => getReflectionsByUpgrade(upgradeId!),
    enabled: !!upgradeId,
  });

/** An upgrade's reminders. */
export const remindersQuery = (upgradeId: string | undefined) =>
  queryOptions({
    queryKey: ['reminders', upgradeId],
    queryFn: () => getReminders(upgradeId!),
    enabled: !!upgradeId,
  });

/**
 * What a section is handed of its read: the page holds the read's one observer and passes its result
 * down, so a section renders the same answer, the same loading state and the same failure as the
 * page sees.
 */
export type SectionRead<T> = Pick<UseQueryResult<T, Error>, 'data' | 'isLoading' | 'error'>;
