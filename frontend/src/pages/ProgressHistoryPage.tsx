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

/**
 * The last seven days of progress across every upgrade, newest first.
 *
 * Two queries rather than one: entries carry an upgrade id but no title, so the upgrades are fetched
 * alongside and indexed by id. An entry whose upgrade has since been deleted still renders, labelled
 * as unknown, rather than disappearing or breaking the list.
 */
const ProgressHistoryPage: React.FC = () => {
  const { data: progress = [], isLoading: pLoading, error: pError } = useQuery({ queryKey: ['allProgress'], queryFn: getWeekProgress });
  const { data: upgrades = [], isLoading: uLoading, error: uError } = useQuery({ queryKey: ['allUpgrades'], queryFn: () => getUpgrades() });

  const upgradeMap: Record<string, HealthUpgrade> = {};
  upgrades.forEach((u) => { upgradeMap[u.id] = u; });

  const sorted = [...progress].sort((a: ProgressEntry, b: ProgressEntry) => new Date(b.date).getTime() - new Date(a.date).getTime());

  if (pLoading || uLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (pError || uError) return <p className="text-red-500 text-center py-10">Failed to load progress history.</p>;

  return (
    <div className="max-w-3xl mx-auto">
      <PageHeader title="Progress History" subtitle="Your progress entries from the last 7 days" />
      {sorted.length === 0 ? (
        <EmptyState icon="📈" title="No progress logged yet" description="Log progress on your active upgrades to see history here." />
      ) : (
        <Card>
          <div className="space-y-3">
            {sorted.map((entry) => {
              const upgrade = upgradeMap[entry.upgradeId];
              return (
                <div key={entry.id} className="flex items-center justify-between p-3 bg-gray-50 rounded-lg text-sm">
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-gray-900 truncate">{upgrade?.title ?? 'Unknown Upgrade'}</p>
                    <p className="text-gray-400 text-xs">{new Date(entry.date).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })}</p>
                  </div>
                  <div className="flex items-center gap-2 ml-4">
                    {entry.completed !== undefined && (
                      <Badge variant={entry.completed ? 'green' : 'red'}>{entry.completed ? '✓ Done' : '✗ Missed'}</Badge>
                    )}
                    {entry.numericValue !== undefined && (
                      <span className="font-semibold text-gray-700">{entry.numericValue} {entry.unit}</span>
                    )}
                    {entry.rating !== undefined && (
                      <span className="text-yellow-500">{'⭐'.repeat(entry.rating)}</span>
                    )}
                    {entry.note && (
                      <span className="text-gray-400 italic truncate max-w-32">{entry.note}</span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </Card>
      )}
    </div>
  );
};

export default ProgressHistoryPage;
