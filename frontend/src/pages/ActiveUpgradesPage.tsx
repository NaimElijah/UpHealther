import React from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getUpgrades, performUpgradeAction } from '../api/upgrades';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import type { UpgradeStatus } from '../types';

const ActiveUpgradesPage: React.FC = () => {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data: upgrades = [], isLoading, error } = useQuery({ queryKey: ['upgrades', 'ACTIVE'], queryFn: () => getUpgrades('ACTIVE') });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: UpgradeStatus }) => performUpgradeAction(id, status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['upgrades'] }),
  });

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <p className="text-red-500 text-center py-10">Failed to load upgrades.</p>;

  return (
    <div className="max-w-4xl mx-auto">
      <PageHeader
        title="Active Upgrades"
        subtitle="Upgrades you are currently working on"
        action={<Button variant="secondary" onClick={() => navigate('/daily-checkin')}>Daily Check-in</Button>}
      />
      {upgrades.length === 0 ? (
        <EmptyState icon="🔥" title="No active upgrades" description="Activate a planned upgrade to start tracking progress." action={<Button onClick={() => navigate('/upgrades/planned')}>View Planned</Button>} />
      ) : (
        <div className="grid sm:grid-cols-2 gap-4">
          {upgrades.map((u) => (
            <UpgradeCard
              key={u.id}
              upgrade={u}
              onStatusChange={(id, status) => statusMutation.mutate({ id, status })}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export default ActiveUpgradesPage;
