import React from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades, performUpgradeAction } from '../api/upgrades';
import PageHeader from '../components/ui/PageHeader';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import Button from '../components/ui/Button';
import { useNavigate } from 'react-router-dom';
import type { UpgradeStatus } from '../types';

const PlannedUpgradesPage: React.FC = () => {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data: upgrades = [], isLoading, error } = useQuery({ queryKey: ['upgrades', 'PLANNED'], queryFn: () => getUpgrades('PLANNED') });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: UpgradeStatus }) => performUpgradeAction(id, status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['upgrades'] }),
  });

  const sorted = [...upgrades].sort((a, b) => {
    if (!a.plannedStartDate) return 1;
    if (!b.plannedStartDate) return -1;
    return new Date(a.plannedStartDate).getTime() - new Date(b.plannedStartDate).getTime();
  });

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <p className="text-red-500 text-center py-10">Failed to load upgrades.</p>;

  return (
    <div className="max-w-4xl mx-auto">
      <PageHeader
        title="Planned Upgrades"
        subtitle="Upgrades scheduled to start soon"
        action={<Button onClick={() => navigate('/upgrades/backlog')}>Browse Backlog</Button>}
      />
      {sorted.length === 0 ? (
        <EmptyState icon="📅" title="No planned upgrades" description="Move ideas from backlog to plan, or create a new upgrade." action={<Button onClick={() => navigate('/upgrades/backlog')}>Go to Backlog</Button>} />
      ) : (
        <div className="grid sm:grid-cols-2 gap-4">
          {sorted.map((u) => (
            <div key={u.id}>
              {u.plannedStartDate && (
                <p className="text-xs text-gray-400 mb-1 ml-1">
                  Starts: {new Date(u.plannedStartDate).toLocaleDateString()}
                </p>
              )}
              <UpgradeCard upgrade={u} onStatusChange={(id, status) => statusMutation.mutate({ id, status })} />
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default PlannedUpgradesPage;
