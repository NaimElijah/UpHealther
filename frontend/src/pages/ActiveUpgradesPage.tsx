import React from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getUpgrades, performUpgradeAction } from '../api/upgrades';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import type { ActionTarget, HealthUpgrade } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError } from '../api/apiError';
import { refreshUpgradeViews } from '../lib/upgradeViews';

/**
 * The upgrades currently running, with the transitions available from `ACTIVE`, and below them the
 * paused ones, with Resume.
 *
 * The paused upgrades are here because Pause is pressed here: no other page lists them, so without this
 * section a pause could not be undone (#176). The API filters by one status at a time, hence two reads.
 * A failed paused read is shown inside its own section, so the running upgrades still render.
 *
 * A transition refreshes every view of upgrades, since it moves an upgrade between this page's lists or
 * onto one it does not show, and changes what the dashboard and the upgrade's own page say (#189). A
 * refused transition is shown on its card with the trace id (NFR-30), and goes away when the next one
 * starts.
 */
const ActiveUpgradesPage: React.FC = () => {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const {
    data: upgrades = [],
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrades', 'ACTIVE'], queryFn: () => getUpgrades('ACTIVE') });
  const {
    data: paused = [],
    isLoading: pausedLoading,
    error: pausedError,
  } = useQuery({
    queryKey: ['upgrades', 'PAUSED'],
    queryFn: () => getUpgrades('PAUSED'),
  });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: ActionTarget }) => performUpgradeAction(id, status),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** The refusal of the last change, for the card it was pressed on: above a long grid it would be out of view. */
  const refusalFor = (id: string) =>
    statusMutation.error && statusMutation.variables?.id === id ? toApiError(statusMutation.error) : undefined;

  /** One card, in either list: both share the page's single mutation, so one change is in flight at a time. */
  const cardFor = (u: HealthUpgrade) => (
    <UpgradeCard
      key={u.id}
      upgrade={u}
      onStatusChange={(id, status) => statusMutation.mutate({ id, status })}
      refusal={refusalFor(u.id)}
      busy={statusMutation.isPending}
    />
  );

  if (isLoading || pausedLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (error) return <ErrorState title="Could not load your upgrades." error={toApiError(error)} />;

  return (
    <PageContainer>
      <PageHeader
        title="Active Upgrades"
        subtitle="Upgrades you are currently working on"
        action={
          <Button variant="secondary" onClick={() => navigate('/daily-checkin')}>
            Daily Check-in
          </Button>
        }
      />
      {upgrades.length > 0 ? (
        <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">{upgrades.map(cardFor)}</div>
      ) : (
        paused.length === 0 && (
          <EmptyState
            icon="🔥"
            title="No active upgrades"
            description="Activate a planned upgrade to start tracking progress."
            action={<Button onClick={() => navigate('/upgrades/planned')}>View Planned</Button>}
          />
        )
      )}
      {(paused.length > 0 || pausedError) && (
        <section aria-labelledby="paused-upgrades-heading" className="mt-8">
          <h2 id="paused-upgrades-heading" className="text-lg font-semibold text-fg mb-3">
            Paused
          </h2>
          {pausedError ? (
            <ErrorState inline title="Could not load your paused upgrades." error={toApiError(pausedError)} />
          ) : (
            <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">{paused.map(cardFor)}</div>
          )}
        </section>
      )}
    </PageContainer>
  );
};

export default ActiveUpgradesPage;
