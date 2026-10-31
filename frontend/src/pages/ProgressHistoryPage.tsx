import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { getWeekProgress } from '../api/progress';
import { getUpgrades } from '../api/upgrades';
import PageHeader from '../components/ui/PageHeader';
import Card from '../components/ui/Card';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import Badge from '../components/ui/Badge';
import type { ProgressEntry, HealthUpgrade } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError } from '../api/apiError';
import { parseLocalDate } from '../lib/localDate';
import { RATING_MAX } from '../components/upgrade/RatingPicker';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * The last seven days of progress across every upgrade, newest first.
 *
 * Two queries rather than one: entries carry an upgrade id but no title, so the upgrades are fetched
 * alongside and indexed by id. The server never returns an entry whose upgrade is gone, because
 * deleting an upgrade deletes its entries (FR-14). An entry can still miss its upgrade here when the
 * `['allProgress']` cache is older than `['allUpgrades']`: a delete refreshes the views of upgrades and
 * not the cached progress (#192). Such an entry renders labelled as unknown, rather than disappearing
 * or breaking the list.
 */
const ProgressHistoryPage: React.FC = () => {
  useDocumentTitle('Progress History');
  const {
    data: progress = [],
    isLoading: pLoading,
    error: pError,
  } = useQuery({ queryKey: ['allProgress'], queryFn: getWeekProgress });
  const {
    data: upgrades = [],
    isLoading: uLoading,
    error: uError,
  } = useQuery({ queryKey: ['allUpgrades'], queryFn: () => getUpgrades() });

  const upgradeMap: Record<string, HealthUpgrade> = {};
  upgrades.forEach((u) => {
    upgradeMap[u.id] = u;
  });

  // `YYYY-MM-DD` sorts as a string; parsing it with `new Date` would read it as UTC (see lib/localDate).
  const sorted = [...progress].sort((a: ProgressEntry, b: ProgressEntry) => b.date.localeCompare(a.date));

  if (pLoading || uLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (pError || uError)
    return (
      <PageContainer>
        <PageHeader title="Progress History" />
        <ErrorState title="Could not load this progress history." error={toApiError(pError ?? uError)} />
      </PageContainer>
    );

  return (
    <PageContainer>
      <PageHeader title="Progress History" subtitle="Your progress entries from the last 7 days" />
      {sorted.length === 0 ? (
        <EmptyState
          icon="📈"
          title="No progress logged yet"
          description="Log progress on your active upgrades to see history here."
        />
      ) : (
        <Card>
          {/* A list, so a screen reader says how many entries there are and moves between them. */}
          <ul className="space-y-3">
            {sorted.map((entry) => {
              const upgrade = upgradeMap[entry.upgradeId];
              return (
                // Wraps rather than truncating, and the details drop below the title on a narrow window:
                // a note cut at 8rem, or a title squeezed to nothing beside it, lost what was written
                // (WCAG 1.4.10).
                <li
                  key={entry.id}
                  className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 p-3 bg-sunken rounded-lg text-sm"
                >
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-fg break-words">{upgrade?.title ?? 'Unknown Upgrade'}</p>
                    <p className="text-fg-faint text-xs">
                      {parseLocalDate(entry.date).toLocaleDateString('en-US', {
                        weekday: 'short',
                        month: 'short',
                        day: 'numeric',
                      })}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 min-w-0">
                    {entry.completed != null && (
                      <Badge variant={entry.completed ? 'green' : 'red'}>
                        <span aria-hidden="true">{entry.completed ? '✓' : '✗'}</span>{' '}
                        {entry.completed ? 'Done' : 'Missed'}
                      </Badge>
                    )}
                    {entry.numericValue != null && (
                      <span className="font-semibold text-fg-muted">
                        {entry.numericValue} {entry.unit}
                      </span>
                    )}
                    {/* The stars are read as a score on its scale; read aloud one by one they gave no scale. */}
                    {entry.rating != null && (
                      <span>
                        <span aria-hidden="true">{'⭐'.repeat(entry.rating)}</span>
                        <span className="sr-only">
                          Rating {entry.rating} of {RATING_MAX}
                        </span>
                      </span>
                    )}
                    {entry.note && <span className="text-fg-faint italic break-words min-w-0">{entry.note}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
        </Card>
      )}
    </PageContainer>
  );
};

export default ProgressHistoryPage;
