import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades } from '../api/upgrades';
import { createProgress } from '../api/progress';
import PageHeader from '../components/ui/PageHeader';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import { useNavigate } from 'react-router-dom';
import type { HealthUpgrade, CreateProgressRequest } from '../types';

type ProgressMap = Record<string, Partial<CreateProgressRequest>>;

const today = () => new Date().toISOString().split('T')[0];

// Avoid sending NaN to the API when the numeric field is cleared.
const numOrUndef = (v: string): number | undefined => {
  const n = parseFloat(v);
  return Number.isNaN(n) ? undefined : n;
};

const DailyCheckinPage: React.FC = () => {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data: upgrades = [], isLoading } = useQuery({ queryKey: ['upgrades', 'ACTIVE'], queryFn: () => getUpgrades('ACTIVE') });
  const [progressMap, setProgressMap] = useState<ProgressMap>({});
  const [submitted, setSubmitted] = useState(false);

  const submitAll = useMutation({
    mutationFn: async (entries: CreateProgressRequest[]) => {
      await Promise.all(entries.map((e) => createProgress(e)));
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['progress'] });
      setSubmitted(true);
    },
  });

  const updateProgress = (id: string, partial: Partial<CreateProgressRequest>) => {
    setProgressMap((prev) => ({ ...prev, [id]: { ...prev[id], ...partial } }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const entries: CreateProgressRequest[] = upgrades.map((u: HealthUpgrade) => ({
      upgradeId: u.id,
      date: today(),
      ...progressMap[u.id],
    }));
    await submitAll.mutateAsync(entries);
  };

  const trackingType = (u: HealthUpgrade) => u.trackingConfig?.trackingType ?? 'BOOLEAN';

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;

  if (submitted) {
    return (
      <div className="max-w-xl mx-auto text-center py-20">
        <div className="text-6xl mb-4">🎉</div>
        <h2 className="text-2xl font-bold text-gray-900 mb-2">Check-in Complete!</h2>
        <p className="text-gray-500 mb-6">Great job tracking your upgrades today.</p>
        <Button onClick={() => navigate('/dashboard')}>Back to Dashboard</Button>
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <PageHeader title="Daily Check-in" subtitle={`Today: ${new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })}`} />

      {upgrades.length === 0 ? (
        <EmptyState icon="✅" title="No active upgrades" description="Activate upgrades to track them here." action={<Button onClick={() => navigate('/upgrades/planned')}>View Planned</Button>} />
      ) : (
        <form onSubmit={handleSubmit} className="space-y-4">
          {upgrades.map((u: HealthUpgrade) => (
            <Card key={u.id} header={u.title}>
              {u.description && <p className="text-sm text-gray-500 mb-3">{u.description}</p>}

              {trackingType(u) === 'BOOLEAN' && (
                <label className="flex items-center gap-3 cursor-pointer">
                  <input
                    type="checkbox"
                    className="w-5 h-5 rounded"
                    checked={progressMap[u.id]?.completed ?? false}
                    onChange={(e) => updateProgress(u.id, { completed: e.target.checked })}
                  />
                  <span className="text-sm font-medium">Completed today</span>
                </label>
              )}

              {trackingType(u) === 'NUMERIC' && (
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    className="rounded-lg border border-gray-300 px-3 py-2 text-sm w-28 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    placeholder="0"
                    value={progressMap[u.id]?.numericValue ?? ''}
                    onChange={(e) => updateProgress(u.id, { numericValue: numOrUndef(e.target.value) })}
                  />
                  <span className="text-sm text-gray-500">{u.trackingConfig?.targetUnit ?? ''}</span>
                </div>
              )}

              {trackingType(u) === 'RATING' && (
                <div className="flex gap-2">
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      key={n}
                      type="button"
                      onClick={() => updateProgress(u.id, { rating: n })}
                      className={`w-10 h-10 rounded-full text-sm font-semibold transition-colors ${progressMap[u.id]?.rating === n ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'}`}
                    >
                      {n}
                    </button>
                  ))}
                </div>
              )}

              {trackingType(u) === 'TEXT' && (
                <textarea
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  rows={2}
                  placeholder="Add your note..."
                  value={progressMap[u.id]?.note ?? ''}
                  onChange={(e) => updateProgress(u.id, { note: e.target.value })}
                />
              )}

              {trackingType(u) !== 'TEXT' && (
                <input
                  type="text"
                  className="mt-3 w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 focus:outline-none focus:ring-1 focus:ring-gray-400"
                  placeholder="Optional note..."
                  value={progressMap[u.id]?.note ?? ''}
                  onChange={(e) => updateProgress(u.id, { note: e.target.value })}
                />
              )}
            </Card>
          ))}

          <Button type="submit" loading={submitAll.isPending} className="w-full" size="lg">
            Submit Check-in
          </Button>
        </form>
      )}
    </div>
  );
};

export default DailyCheckinPage;
