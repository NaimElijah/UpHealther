import React, { useId, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades } from '../api/upgrades';
import { createProgress } from '../api/progress';
import PageHeader from '../components/ui/PageHeader';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import { numOrUndef } from '../components/upgrade/formInput';
import { useNavigate } from 'react-router-dom';
import type { HealthUpgrade, CreateProgressRequest } from '../types';
import PageContainer from '../components/ui/PageContainer';
import PageHeading from '../components/ui/PageHeading';
import { todayLocal } from '../lib/localDate';
import ErrorState from '../components/ui/ErrorState';
import { toApiError } from '../api/apiError';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/** Draft entries being filled in, keyed by upgrade id, until the whole form is submitted. */
type ProgressMap = Record<string, Partial<CreateProgressRequest>>;

/**
 * Logs progress for every active upgrade in one pass.
 *
 * The entries are gathered locally and submitted together, so the user fills the form once rather than
 * saving each upgrade separately. Every active upgrade is sent, touched or not: an untouched one is
 * posted with nothing but its upgrade and the day, and since an upgrade takes one entry a day (BR-6), it
 * then counts as missed and cannot be corrected later that day. Whether an untouched upgrade should be
 * sent at all is #55's decision.
 *
 * A failed load or submit is shown with its trace id (NFR-30). A failed load is not the empty state, and
 * a failed submit keeps the form, saying the check-in was not *fully* saved: one refusal rejects the
 * whole submit while the other entries may already be stored. Loading today's entries first, so that
 * conflict cannot arise, is #55's decision.
 */
const DailyCheckinPage: React.FC = () => {
  useDocumentTitle('Daily Check-in');
  const qc = useQueryClient();
  const navigate = useNavigate();
  const {
    data: upgrades = [],
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrades', 'ACTIVE'], queryFn: () => getUpgrades('ACTIVE') });
  const [progressMap, setProgressMap] = useState<ProgressMap>({});
  const [submitted, setSubmitted] = useState(false);
  // One prefix for the page; each upgrade's fields add its id, so every label and unit names its own field.
  const fieldPrefix = useId();
  const fieldId = (upgradeId: string, part: string) => `${fieldPrefix}-${upgradeId}-${part}`;

  const submitAll = useMutation({
    mutationFn: async (entries: CreateProgressRequest[]) => {
      await Promise.all(entries.map((e) => createProgress(e)));
    },
    // Settled rather than succeeded: the entries are posted together, so when one is refused the others
    // may already be stored, and the progress every other page caches is stale either way.
    onSettled: () => qc.invalidateQueries({ queryKey: ['progress'] }),
    onSuccess: () => setSubmitted(true),
  });

  /** Merges a field change into one upgrade's draft entry, leaving the others untouched. */
  const updateProgress = (id: string, partial: Partial<CreateProgressRequest>) => {
    setProgressMap((prev) => ({ ...prev, [id]: { ...prev[id], ...partial } }));
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const entries: CreateProgressRequest[] = upgrades.map((u: HealthUpgrade) => ({
      upgradeId: u.id,
      date: todayLocal(),
      ...progressMap[u.id],
    }));
    submitAll.mutate(entries);
  };

  const trackingType = (u: HealthUpgrade) => u.trackingConfig?.trackingType ?? 'BOOLEAN';

  if (isLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (error) return <ErrorState title="Could not load your active upgrades." error={toApiError(error)} />;

  if (submitted) {
    return (
      <PageContainer width="narrow" className="text-center py-20">
        <div className="text-6xl mb-4">🎉</div>
        {/* The page's heading now, and it takes focus: the form, and the submit button that had focus, are
            gone, and focus would otherwise fall to <body>. */}
        <PageHeading takeFocus className="text-2xl font-bold text-fg mb-2">
          Check-in Complete!
        </PageHeading>
        <p className="text-fg-subtle mb-6">Great job tracking your upgrades today.</p>
        <Button onClick={() => navigate('/dashboard')}>Back to Dashboard</Button>
      </PageContainer>
    );
  }

  return (
    <PageContainer width="narrow">
      <PageHeader
        title="Daily Check-in"
        subtitle={`Today: ${new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })}`}
      />

      {upgrades.length === 0 ? (
        <EmptyState
          icon="✅"
          title="No active upgrades"
          description="Activate upgrades to track them here."
          action={<Button onClick={() => navigate('/upgrades/planned')}>View Planned</Button>}
        />
      ) : (
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Each upgrade's fields are a group named by its title, so a field read on its own still says which
              upgrade it belongs to (WCAG 1.3.1). */}
          {upgrades.map((u: HealthUpgrade) => (
            <div key={u.id} role="group" aria-labelledby={fieldId(u.id, 'title')}>
              <Card header={<span id={fieldId(u.id, 'title')}>{u.title}</span>}>
                {u.description && <p className="text-sm text-fg-subtle mb-3">{u.description}</p>}

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
                  <div className="flex flex-col gap-1">
                    <label htmlFor={fieldId(u.id, 'amount')} className="text-sm font-medium text-fg-muted">
                      Amount
                    </label>
                    <div className="flex items-center gap-2">
                      <input
                        id={fieldId(u.id, 'amount')}
                        type="number"
                        aria-describedby={u.trackingConfig?.targetUnit ? fieldId(u.id, 'unit') : undefined}
                        className="rounded-lg border border-line-strong px-3 py-2 text-sm w-28 focus:outline-none focus:ring-2"
                        placeholder="0"
                        value={progressMap[u.id]?.numericValue ?? ''}
                        onChange={(e) => updateProgress(u.id, { numericValue: numOrUndef(e.target.value) })}
                      />
                      {u.trackingConfig?.targetUnit && (
                        <span id={fieldId(u.id, 'unit')} className="text-sm text-fg-subtle">
                          {u.trackingConfig.targetUnit}
                        </span>
                      )}
                    </div>
                  </div>
                )}

                {trackingType(u) === 'RATING' && (
                  <div className="flex gap-2">
                    {[1, 2, 3, 4, 5].map((n) => (
                      <button
                        key={n}
                        type="button"
                        onClick={() => updateProgress(u.id, { rating: n })}
                        className={`w-10 h-10 rounded-full text-sm font-semibold transition-colors ${progressMap[u.id]?.rating === n ? 'bg-brand text-fg-inverted' : 'bg-muted text-fg-muted hover:bg-muted-strong'}`}
                      >
                        {n}
                      </button>
                    ))}
                  </div>
                )}

                {trackingType(u) === 'TEXT' && (
                  <div className="flex flex-col gap-1">
                    <label htmlFor={fieldId(u.id, 'note')} className="text-sm font-medium text-fg-muted">
                      Note
                    </label>
                    <textarea
                      id={fieldId(u.id, 'note')}
                      className="w-full rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2"
                      rows={2}
                      placeholder="Add your note..."
                      value={progressMap[u.id]?.note ?? ''}
                      onChange={(e) => updateProgress(u.id, { note: e.target.value })}
                    />
                  </div>
                )}

                {trackingType(u) !== 'TEXT' && (
                  <div className="mt-3 flex flex-col gap-1">
                    <label htmlFor={fieldId(u.id, 'note')} className="text-sm font-medium text-fg-muted">
                      Optional note
                    </label>
                    <input
                      id={fieldId(u.id, 'note')}
                      type="text"
                      className="w-full rounded-lg border border-line px-3 py-2 text-sm text-fg-subtle focus:outline-none focus:ring-1"
                      placeholder="Optional note..."
                      value={progressMap[u.id]?.note ?? ''}
                      onChange={(e) => updateProgress(u.id, { note: e.target.value })}
                    />
                  </div>
                )}
              </Card>
            </div>
          ))}

          {submitAll.error && (
            <ErrorState inline title="Your check-in was not fully saved." error={toApiError(submitAll.error)} />
          )}
          <Button type="submit" loading={submitAll.isPending} className="w-full" size="lg">
            Submit Check-in
          </Button>
        </form>
      )}
    </PageContainer>
  );
};

export default DailyCheckinPage;
