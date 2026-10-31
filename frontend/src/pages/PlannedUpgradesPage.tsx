import React from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades, performUpgradeAction } from '../api/upgrades';
import PageHeader from '../components/ui/PageHeader';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import Button from '../components/ui/Button';
import { useNavigate } from 'react-router-dom';
import type { ActionTarget } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError } from '../api/apiError';
import { parseLocalDate } from '../lib/localDate';
import { refreshUpgradeViews } from '../lib/upgradeViews';
import { useFocusWhenReady } from '../hooks/useFocusWhenReady';
import { findPageHeading } from '../components/ui/pageHeadingFocus';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * Upgrades committed to a start date but not yet running, soonest first.
 *
 * Sorted client-side because the API returns them unordered; upgrades with no planned date sort last
 * rather than first, so an unscheduled one does not head a list meant to read as a timeline. A refused
 * activation is shown on its card with the trace id (NFR-30).
 */
const PlannedUpgradesPage: React.FC = () => {
  useDocumentTitle('Planned Upgrades');
  const qc = useQueryClient();
  const navigate = useNavigate();
  const {
    data: upgrades = [],
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrades', 'PLANNED'], queryFn: () => getUpgrades('PLANNED') });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: ActionTarget }) => performUpgradeAction(id, status),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** The refusal of the last change, for the card it was pressed on: above a long grid it would be out of view. */
  const refusalFor = (id: string) =>
    statusMutation.error && statusMutation.variables?.id === id ? toApiError(statusMutation.error) : undefined;

  const focusWhenReady = useFocusWhenReady();
  // An activated upgrade leaves this list, and its Activate with it; focus then goes to the page's
  // heading rather than falling to <body>.
  const changeStatus = (id: string, status: ActionTarget) => {
    const pressed = document.activeElement;
    statusMutation.mutate(
      { id, status },
      { onSuccess: () => focusWhenReady(() => (pressed?.isConnected ? undefined : findPageHeading())) },
    );
  };

  const sorted = [...upgrades].sort((a, b) => {
    if (!a.plannedStartDate) return 1;
    if (!b.plannedStartDate) return -1;
    // `YYYY-MM-DD` sorts as a string; parsing it with `new Date` would read it as UTC (see lib/localDate).
    return a.plannedStartDate.localeCompare(b.plannedStartDate);
  });

  if (isLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (error) return <ErrorState title="Could not load your upgrades." error={toApiError(error)} />;

  return (
    <PageContainer>
      <PageHeader
        title="Planned Upgrades"
        subtitle="Upgrades scheduled to start soon"
        action={<Button onClick={() => navigate('/upgrades/backlog')}>Browse Backlog</Button>}
      />
      {sorted.length === 0 ? (
        <EmptyState
          icon="📅"
          title="No planned upgrades"
          description="Move ideas from backlog to plan, or create a new upgrade."
          action={<Button onClick={() => navigate('/upgrades/backlog')}>Go to Backlog</Button>}
        />
      ) : (
        <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {sorted.map((u) => (
            <div key={u.id}>
              {u.plannedStartDate && (
                <p className="text-xs text-fg-faint mb-1 ml-1">
                  Starts: {parseLocalDate(u.plannedStartDate).toLocaleDateString()}
                </p>
              )}
              <UpgradeCard
                upgrade={u}
                onStatusChange={changeStatus}
                refusal={refusalFor(u.id)}
                busy={statusMutation.isPending}
                pendingTransition={
                  statusMutation.isPending && statusMutation.variables?.id === u.id
                    ? statusMutation.variables.status
                    : undefined
                }
              />
            </div>
          ))}
        </div>
      )}
    </PageContainer>
  );
};

export default PlannedUpgradesPage;
