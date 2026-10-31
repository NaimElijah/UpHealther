import { QueryClient } from '@tanstack/react-query';
import { onSessionEnd } from '../api/session';

/**
 * Builds the server-state cache the whole application shares.
 *
 * A single retry, because a failed call here is usually a 4xx that will fail identically; and a 30
 * second staleness window, which keeps navigation between pages from refetching data that was just
 * shown while still picking up changes made in another tab reasonably soon.
 *
 * A factory rather than a module-level instance, so a test can stand up the cache the app really runs
 * with — the staleness window included — without sharing one between tests.
 *
 * Side effect: the client is emptied whenever `endSession` runs, for the life of the page, so a page
 * the back/forward cache restores holds nothing of the previous user's (S5.D1, #339).
 *
 * @returns a new client with the application's query defaults
 */
export function createQueryClient(): QueryClient {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: 1,
        staleTime: 30_000,
      },
    },
  });
  onSessionEnd(() => queryClient.clear());
  return queryClient;
}
