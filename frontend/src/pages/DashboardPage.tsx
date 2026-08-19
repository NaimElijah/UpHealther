import React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getDashboard } from '../api/dashboard';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import EmptyState from '../components/ui/EmptyState';
import { performUpgradeAction } from '../api/upgrades';
import type { UpgradeStatus } from '../types';
import PageContainer from '../components/ui/PageContainer';

/**
 * Landing page after sign-in: counts, the weekly rate, streaks, overdue warnings and today's upgrades.
 *
 * Everything comes from the single `/api/dashboard` call, so the sections cannot disagree with one
 * another. A transition performed from a card invalidates that one query, which refreshes the whole
 * page at once.
 */
const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
  });

  /** Performs a lifecycle transition from a card, then refetches the dashboard it appeared on. */
  const handleStatusChange = async (id: string, status: UpgradeStatus) => {
    await performUpgradeAction(id, status);
    await queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <p className="text-danger-fg text-center py-10">Failed to load dashboard.</p>;

  // Rounded, not scaled: the API sends a percentage already (see DashboardDto.weeklyCompletionRate).
  const completionPct = Math.round(data?.weeklyCompletionRate ?? 0);
  const streakEntries = Object.entries(data?.streaks ?? {});

  return (
    <PageContainer className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-fg break-words">
            Good {new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 18 ? 'afternoon' : 'evening'},{' '}
            {user?.name?.split(' ')[0]} 👋
          </h1>
          <p className="text-fg-subtle mt-1">{new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })}</p>
        </div>
        <Button className="shrink-0" onClick={() => navigate('/upgrades/backlog')}>+ Add Upgrade</Button>
      </div>

      {(data?.overdueUpgrades?.length ?? 0) > 0 && (
        <div className="bg-danger-soft border border-danger-line rounded-xl p-4 flex items-center gap-3">
          <span className="text-2xl">⚠️</span>
          <div>
            <p className="font-medium text-danger-fg">{data!.overdueUpgrades.length} overdue upgrade{data!.overdueUpgrades.length > 1 ? 's' : ''}</p>
            <p className="text-sm text-danger-fg">Some upgrades are past their target date.</p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="text-center">
          <div className="text-3xl font-bold text-brand-fg">{data?.activeUpgrades.length ?? 0}</div>
          <div className="text-sm text-fg-subtle mt-1">Active</div>
        </Card>
        <Card className="text-center">
          <div className="text-3xl font-bold text-accent-fg">{data?.plannedUpgrades.length ?? 0}</div>
          <div className="text-sm text-fg-subtle mt-1">Planned</div>
        </Card>
        <Card className="text-center">
          <div className="text-3xl font-bold text-success-fg">{completionPct}%</div>
          <div className="text-sm text-fg-subtle mt-1">Weekly Rate</div>
        </Card>
        <Card className="text-center">
          <div className="text-3xl font-bold text-streak-fg">{streakEntries.length}</div>
          <div className="text-sm text-fg-subtle mt-1">Streaks</div>
        </Card>
      </div>

      <Card header="Weekly Completion Rate">
        <div className="flex items-center gap-4">
          <div className="flex-1 bg-muted-strong rounded-full h-4 overflow-hidden">
            <div
              className="h-full bg-success rounded-full transition-all duration-500"
              style={{ width: `${completionPct}%` }}
            />
          </div>
          <span className="text-sm font-semibold text-fg-muted w-12 text-right">{completionPct}%</span>
        </div>
      </Card>

      <Card header="Today's Health Moves">
        {(data?.todayUpgrades?.length ?? 0) === 0 ? (
          <EmptyState icon="✅" title="All done for today!" description="No active upgrades to track today." />
        ) : (
          <div className="space-y-3">
            {data!.todayUpgrades.map((u) => (
              <UpgradeCard key={u.id} upgrade={u} onStatusChange={handleStatusChange} />
            ))}
            <Button variant="secondary" onClick={() => navigate('/daily-checkin')} className="w-full mt-2">
              Go to Daily Check-in
            </Button>
          </div>
        )}
      </Card>

      <Card header="Active Upgrades">
        {(data?.activeUpgrades?.length ?? 0) === 0 ? (
          <EmptyState icon="🔥" title="No active upgrades" description="Activate a planned upgrade to get started." action={<Button onClick={() => navigate('/upgrades/planned')}>View Planned</Button>} />
        ) : (
          <div className="grid sm:grid-cols-2 gap-4">
            {data!.activeUpgrades.map((u) => (
              <UpgradeCard key={u.id} upgrade={u} onStatusChange={handleStatusChange} />
            ))}
          </div>
        )}
      </Card>

      {streakEntries.length > 0 && (
        <Card header="Current Streaks 🔥">
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
            {streakEntries.map(([id, count]) => (
              <div key={id} className="bg-streak-soft rounded-lg p-3 text-center">
                <div className="text-2xl font-bold text-streak-fg">{count}</div>
                <div className="text-xs text-fg-subtle mt-1">days</div>
              </div>
            ))}
          </div>
        </Card>
      )}

      {(data?.recentlyCompleted?.length ?? 0) > 0 && (
        <Card header="Recently Completed 🎉">
          <div className="space-y-2">
            {data!.recentlyCompleted.map((u) => (
              <div key={u.id} className="flex items-center gap-3 p-2 rounded-lg bg-sunken">
                <span className="text-success-fg text-lg">✓</span>
                <span className="text-sm font-medium text-fg-muted">{u.title}</span>
              </div>
            ))}
          </div>
        </Card>
      )}
    </PageContainer>
  );
};

export default DashboardPage;
