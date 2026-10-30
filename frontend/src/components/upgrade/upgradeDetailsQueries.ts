import { queryOptions, useQuery } from '@tanstack/react-query';
import { getProgressByUpgrade, getStreak } from '../../api/progress';
import { getReflectionsByUpgrade } from '../../api/reflections';
import { getReminders } from '../../api/reminders';

/**
 * The reads of an upgrade page's sections, each defined once: its key, how it is fetched, and when.
 *
 * Each section reads its own, and so does the page, through {@link useStartSectionReads}. The page
 * renders the sections only once the upgrade has loaded, so a read that started in a section would
 * wait for the upgrade's. The page's observer starts all of them with it instead, at mount, in
 * parallel. A section adds {@link READ_STARTED_BY_PAGE} to its own observer, so it never starts a fetch
 * the page's would not have.
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
 * Observes every section's read from the moment the page mounts, so each starts beside the upgrade's
 * read rather than after it. The page reads none of the results; the sections do.
 *
 * @param upgradeId the id from the route
 */
export function useStartSectionReads(upgradeId: string | undefined): void {
  useQuery(progressQuery(upgradeId));
  useQuery(reflectionsQuery(upgradeId));
  useQuery(streakQuery(upgradeId));
  useQuery(remindersQuery(upgradeId));
}

/**
 * Spread into a section's own read. The page's observer already decided, as it mounted, whether to
 * fetch, and retries and refetches the way it always has. A second observer that fetched again on
 * mount would add a request whenever the answer was stale or had failed.
 */
export const READ_STARTED_BY_PAGE = { refetchOnMount: false, retryOnMount: false } as const;
