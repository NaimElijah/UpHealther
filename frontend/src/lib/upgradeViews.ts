import type { QueryClient } from '@tanstack/react-query';

/**
 * Every cache key under which a page shows upgrades: the per-status lists, the dashboard, one
 * upgrade's details and the progress history's title lookup.
 *
 * Prefixes, so `['upgrades']` covers every status and `['upgrade']` every upgrade's details.
 */
const UPGRADE_VIEW_KEYS = [['upgrades'], ['dashboard'], ['upgrade'], ['allUpgrades']] as const;

/**
 * Marks every view of upgrades out of date after a write that changed one (#189, ADR-029).
 *
 * An upgrade's status shows on more pages than the one it was changed on, and the cache keeps an
 * answer fresh for thirty seconds, so refreshing only the current page left the others showing a
 * state the upgrade had already left. Invalidation refetches only the queries a mounted page is
 * using; the rest are marked stale and refetch when their page next opens, so marking all of them
 * costs nothing for the pages that are closed.
 *
 * @param queryClient the application's query cache
 * @returns a promise that settles once the mounted views have refetched, for a caller that must not
 *          act before the page shows the change
 */
export async function refreshUpgradeViews(queryClient: QueryClient): Promise<void> {
  await Promise.all(UPGRADE_VIEW_KEYS.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}
