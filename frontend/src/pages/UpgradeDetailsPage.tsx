import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgradeById } from '../api/upgrades';
import { getProgressByUpgrade, createProgress, getStreak } from '../api/progress';
import { getReflectionsByUpgrade, createReflection } from '../api/reflections';
import { saveTrackingConfig, type SaveTrackingConfigRequest } from '../api/trackingConfig';
import { getReminders, createReminder, deleteReminder } from '../api/reminders';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Select from '../components/ui/Select';
import Modal from '../components/ui/Modal';
import UpgradeStatusBadge from '../components/upgrade/UpgradeStatusBadge';
import UpgradeTypeBadge from '../components/upgrade/UpgradeTypeBadge';
import Badge from '../components/ui/Badge';
import { difficultyVariant, UNKNOWN_DIFFICULTY_VARIANT } from '../components/upgrade/upgradeMeta';
import type { CreateProgressRequest, CreateReflectionRequest, TrackingType, Frequency } from '../types';
import PageContainer from '../components/ui/PageContainer';

/** Today as `YYYY-MM-DD`, the date format the progress and reflection APIs expect. */
const today = () => new Date().toISOString().split('T')[0];
/**
 * Day tokens for the reminder day picker, in week order.
 *
 * Three-letter and upper case because that is the form the API stores; selecting none means the
 * reminder fires every day.
 */
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/**
 * Parses a decimal field, treating unparseable input as absent.
 *
 * A cleared number input yields an empty string, and `parseFloat('')` is NaN — which would serialise
 * to `null` and be read as a logged value rather than as no value.
 */
const numOrUndef = (v: string): number | undefined => {
  const n = parseFloat(v);
  return Number.isNaN(n) ? undefined : n;
};
/** Integer counterpart of {@link numOrUndef}, for the rating fields. */
const intOrUndef = (v: string): number | undefined => {
  const n = parseInt(v, 10);
  return Number.isNaN(n) ? undefined : n;
};

/**
 * Everything about one upgrade: its details, progress history, streak, reflections, reminders and
 * tracking configuration.
 *
 * The busiest page in the app, and the only place several of these can be edited at all. Each concern
 * is its own query and its own mutation, so saving a reflection does not refetch the progress history;
 * what they share is the upgrade id from the route.
 */
const UpgradeDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [progressOpen, setProgressOpen] = useState(false);
  const [reflectionOpen, setReflectionOpen] = useState(false);
  const [trackingOpen, setTrackingOpen] = useState(false);

  const [trackingForm, setTrackingForm] = useState<SaveTrackingConfigRequest>({
    trackingType: 'BOOLEAN',
    frequency: 'DAILY',
    requiredDaily: true,
  });

  const [progressForm, setProgressForm] = useState<CreateProgressRequest>({
    upgradeId: id ?? '',
    date: today(),
    completed: false,
    note: '',
  });

  const [reflectionForm, setReflectionForm] = useState<Omit<CreateReflectionRequest, 'upgradeId'>>({
    date: today(),
    whatWorked: '',
    whatDidNotWork: '',
    nextAdjustment: '',
    difficultyRating: 3,
    benefitRating: 3,
  });

  const [reminderTime, setReminderTime] = useState('09:00');
  const [reminderDays, setReminderDays] = useState<string[]>([]);
  const toggleDay = (d: string) =>
    setReminderDays((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d]));

  const { data: upgrade, isLoading, error } = useQuery({ queryKey: ['upgrade', id], queryFn: () => getUpgradeById(id!), enabled: !!id });
  const { data: progress = [] } = useQuery({ queryKey: ['progress', id], queryFn: () => getProgressByUpgrade(id!), enabled: !!id });
  const { data: reflections = [] } = useQuery({ queryKey: ['reflections', id], queryFn: () => getReflectionsByUpgrade(id!), enabled: !!id });
  const { data: streak = { current: 0, longest: 0 } } = useQuery({ queryKey: ['streak', id], queryFn: () => getStreak(id!), enabled: !!id });
  const { data: reminders = [] } = useQuery({ queryKey: ['reminders', id], queryFn: () => getReminders(id!), enabled: !!id });

  const createReminderMut = useMutation({
    mutationFn: () => createReminder(id!, { reminderTime, daysOfWeek: reminderDays, enabled: true }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['reminders', id] }); setReminderDays([]); },
  });
  const deleteReminderMut = useMutation({
    mutationFn: (reminderId: string) => deleteReminder(reminderId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reminders', id] }),
  });

  const progressMutation = useMutation({
    mutationFn: createProgress,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['progress', id] }); qc.invalidateQueries({ queryKey: ['streak', id] }); setProgressOpen(false); },
  });

  const reflectionMutation = useMutation({
    mutationFn: (body: Omit<CreateReflectionRequest, 'upgradeId'>) => createReflection(id!, body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['reflections', id] }); setReflectionOpen(false); },
  });

  const trackingMutation = useMutation({
    mutationFn: (req: SaveTrackingConfigRequest) => saveTrackingConfig(id!, req),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['upgrade', id] }); setTrackingOpen(false); },
  });

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error || !upgrade) return <p className="text-danger-fg text-center py-10">Failed to load this upgrade.</p>;

  return (
    <PageContainer width="narrow" className="space-y-6">
      <button onClick={() => navigate(-1)} className="text-sm text-brand-fg hover:underline">← Back</button>

      <Card>
        <div className="flex flex-wrap gap-2 mb-3">
          <UpgradeStatusBadge status={upgrade.status} />
          <UpgradeTypeBadge type={upgrade.type} />
          <Badge variant={difficultyVariant[upgrade.difficulty] ?? UNKNOWN_DIFFICULTY_VARIANT}>
            {upgrade.difficulty}
          </Badge>
        </div>
        <h1 className="text-2xl font-bold text-fg">{upgrade.title}</h1>
        {upgrade.description && <p className="text-fg-subtle mt-2">{upgrade.description}</p>}
        {upgrade.motivation && (
          <div className="mt-4 bg-brand-soft rounded-lg p-3">
            <p className="text-sm text-brand-fg"><strong>Motivation:</strong> {upgrade.motivation}</p>
          </div>
        )}
        {upgrade.successCriteria && (
          <div className="mt-2 bg-success-soft rounded-lg p-3">
            <p className="text-sm text-success-fg"><strong>Success Criteria:</strong> {upgrade.successCriteria}</p>
          </div>
        )}
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm text-fg-subtle">
          {upgrade.plannedStartDate && <div><span className="font-medium">Planned Start:</span> {new Date(upgrade.plannedStartDate).toLocaleDateString()}</div>}
          {upgrade.actualStartDate && <div><span className="font-medium">Actual Start:</span> {new Date(upgrade.actualStartDate).toLocaleDateString()}</div>}
          {upgrade.targetEndDate && <div><span className="font-medium">Target End:</span> {new Date(upgrade.targetEndDate).toLocaleDateString()}</div>}
        </div>
      </Card>

      {streak.current > 0 && (
        <div className="bg-streak-soft border border-streak-line rounded-xl p-4 flex items-center gap-3">
          <span className="text-3xl">🔥</span>
          <div>
            <p className="font-semibold text-streak-fg">{streak.current}-day streak!</p>
            <p className="text-sm text-streak-fg">Longest streak: {streak.longest} day{streak.longest === 1 ? '' : 's'}. Keep it up!</p>
          </div>
        </div>
      )}

      <Card header={
        <div className="flex items-center justify-between">
          <span>Tracking Configuration</span>
          <Button size="sm" variant="secondary" onClick={() => {
            setTrackingForm({
              trackingType: upgrade.trackingConfig?.trackingType ?? 'BOOLEAN',
              frequency: upgrade.trackingConfig?.frequency ?? 'DAILY',
              targetNumericValue: upgrade.trackingConfig?.targetNumericValue,
              targetUnit: upgrade.trackingConfig?.targetUnit,
              requiredDaily: upgrade.trackingConfig?.requiredDaily ?? true,
            });
            setTrackingOpen(true);
          }}>
            {upgrade.trackingConfig ? 'Edit' : 'Configure'}
          </Button>
        </div>
      }>
        {upgrade.trackingConfig ? (
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div><span className="text-fg-subtle">Type:</span> <span className="font-medium">{upgrade.trackingConfig.trackingType}</span></div>
            <div><span className="text-fg-subtle">Frequency:</span> <span className="font-medium">{upgrade.trackingConfig.frequency}</span></div>
            {upgrade.trackingConfig.targetNumericValue != null && (
              <div><span className="text-fg-subtle">Target:</span> <span className="font-medium">{upgrade.trackingConfig.targetNumericValue} {upgrade.trackingConfig.targetUnit}</span></div>
            )}
          </div>
        ) : (
          <p className="text-fg-subtle text-sm text-center py-4">No tracking configured yet. Configure how you'll track this upgrade.</p>
        )}
      </Card>

      <Card header={<span>Reminders ({reminders.length})</span>}>
        {reminders.length === 0 ? (
          <p className="text-fg-subtle text-sm mb-4">No reminders yet. Add one to get notified.</p>
        ) : (
          <div className="space-y-2 mb-4">
            {reminders.map((r) => (
              <div key={r.id} className="flex items-center justify-between p-2 bg-sunken rounded-lg text-sm">
                <div className="flex items-center gap-2">
                  <span className="font-medium">⏰ {r.reminderTime.slice(0, 5)}</span>
                  <span className="text-fg-subtle">{r.daysOfWeek.length ? r.daysOfWeek.join(', ') : 'Every day'}</span>
                  {!r.enabled && <Badge variant="gray">disabled</Badge>}
                </div>
                <button
                  onClick={() => deleteReminderMut.mutate(r.id)}
                  className="text-danger-fg hover:underline text-xs font-medium"
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        )}
        <form
          onSubmit={(e) => { e.preventDefault(); createReminderMut.mutate(); }}
          className="flex flex-wrap items-end gap-3 border-t border-line-subtle pt-3"
        >
          <Input label="Time" type="time" value={reminderTime} onChange={(e) => setReminderTime(e.target.value)} />
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1">Days <span className="text-fg-faint">(none = every day)</span></label>
            <div className="flex gap-1">
              {DAYS.map((d) => (
                <button
                  key={d}
                  type="button"
                  title={d}
                  onClick={() => toggleDay(d)}
                  className={`w-9 h-9 rounded-lg text-xs font-medium transition-colors ${reminderDays.includes(d) ? 'bg-brand text-fg-inverted' : 'bg-muted text-fg-subtle hover:bg-muted-strong'}`}
                >
                  {d[0]}
                </button>
              ))}
            </div>
          </div>
          <Button type="submit" loading={createReminderMut.isPending}>Add reminder</Button>
        </form>
      </Card>

      <Card header={
        <div className="flex items-center justify-between">
          <span>Progress History ({progress.length})</span>
          <Button size="sm" onClick={() => setProgressOpen(true)}>+ Log Progress</Button>
        </div>
      }>
        {progress.length === 0 ? (
          <p className="text-fg-subtle text-sm text-center py-4">No progress logged yet.</p>
        ) : (
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {[...progress].reverse().map((p) => (
              <div key={p.id} className="flex items-center justify-between p-3 bg-sunken rounded-lg text-sm">
                <span className="text-fg-subtle">{new Date(p.date).toLocaleDateString()}</span>
                <div className="flex items-center gap-3">
                  {p.completed !== undefined && <Badge variant={p.completed ? 'green' : 'red'}>{p.completed ? 'Done' : 'Missed'}</Badge>}
                  {p.numericValue !== undefined && <span className="font-medium">{p.numericValue} {p.unit}</span>}
                  {p.rating !== undefined && <span>⭐ {p.rating}/5</span>}
                  {p.note && <span className="text-fg-subtle italic truncate max-w-32">{p.note}</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card header={
        <div className="flex items-center justify-between">
          <span>Reflections ({reflections.length})</span>
          <Button size="sm" variant="secondary" onClick={() => setReflectionOpen(true)}>+ Add Reflection</Button>
        </div>
      }>
        {reflections.length === 0 ? (
          <p className="text-fg-subtle text-sm text-center py-4">No reflections yet.</p>
        ) : (
          <div className="space-y-3">
            {[...reflections].reverse().map((r) => (
              <div key={r.id} className="p-3 bg-sunken rounded-lg text-sm space-y-1">
                <p className="text-fg-faint">{new Date(r.date).toLocaleDateString()}</p>
                {r.whatWorked && <p><strong>✅ What worked:</strong> {r.whatWorked}</p>}
                {r.whatDidNotWork && <p><strong>❌ What didn't:</strong> {r.whatDidNotWork}</p>}
                {r.nextAdjustment && <p><strong>🔄 Next:</strong> {r.nextAdjustment}</p>}
                <div className="flex gap-4">
                  {r.difficultyRating && <span>Difficulty: {r.difficultyRating}/5</span>}
                  {r.benefitRating && <span>Benefit: {r.benefitRating}/5</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Modal isOpen={progressOpen} onClose={() => setProgressOpen(false)} title="Log Progress">
        <form onSubmit={(e) => { e.preventDefault(); progressMutation.mutate(progressForm); }} className="space-y-4">
          <Input label="Date" type="date" value={progressForm.date} onChange={(e) => setProgressForm({ ...progressForm, date: e.target.value })} />
          {(!upgrade.trackingConfig || upgrade.trackingConfig.trackingType === 'BOOLEAN') && (
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={progressForm.completed ?? false} onChange={(e) => setProgressForm({ ...progressForm, completed: e.target.checked })} className="w-4 h-4 rounded" />
              <span className="text-sm font-medium text-fg-muted">Completed</span>
            </label>
          )}
          {upgrade.trackingConfig?.trackingType === 'NUMERIC' && (
            <Input label={`Value (${upgrade.trackingConfig.targetUnit ?? ''})`} type="number" value={progressForm.numericValue ?? ''} onChange={(e) => setProgressForm({ ...progressForm, numericValue: numOrUndef(e.target.value) })} />
          )}
          {upgrade.trackingConfig?.trackingType === 'RATING' && (
            <div>
              <label className="text-sm font-medium text-fg-muted">Rating (1-5)</label>
              <div className="flex gap-2 mt-2">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button key={n} type="button" onClick={() => setProgressForm({ ...progressForm, rating: n })} className={`w-10 h-10 rounded-full text-sm font-semibold transition-colors ${progressForm.rating === n ? 'bg-brand text-fg-inverted' : 'bg-muted text-fg-muted hover:bg-muted-strong'}`}>{n}</button>
                ))}
              </div>
            </div>
          )}
          <Input label="Note" value={progressForm.note ?? ''} onChange={(e) => setProgressForm({ ...progressForm, note: e.target.value })} placeholder="Optional note" />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setProgressOpen(false)}>Cancel</Button>
            <Button type="submit" loading={progressMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={reflectionOpen} onClose={() => setReflectionOpen(false)} title="Add Reflection">
        <form onSubmit={(e) => { e.preventDefault(); reflectionMutation.mutate(reflectionForm); }} className="space-y-4">
          <Input label="Date" type="date" value={reflectionForm.date} onChange={(e) => setReflectionForm({ ...reflectionForm, date: e.target.value })} />
          <div>
            <label className="text-sm font-medium text-fg-muted">What worked?</label>
            <textarea className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2" rows={2} value={reflectionForm.whatWorked ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, whatWorked: e.target.value })} />
          </div>
          <div>
            <label className="text-sm font-medium text-fg-muted">What didn't work?</label>
            <textarea className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2" rows={2} value={reflectionForm.whatDidNotWork ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, whatDidNotWork: e.target.value })} />
          </div>
          <div>
            <label className="text-sm font-medium text-fg-muted">Next adjustment?</label>
            <textarea className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2" rows={2} value={reflectionForm.nextAdjustment ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, nextAdjustment: e.target.value })} />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input label="Difficulty (1-5)" type="number" min={1} max={5} value={reflectionForm.difficultyRating ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, difficultyRating: intOrUndef(e.target.value) })} />
            <Input label="Benefit (1-5)" type="number" min={1} max={5} value={reflectionForm.benefitRating ?? ''} onChange={(e) => setReflectionForm({ ...reflectionForm, benefitRating: intOrUndef(e.target.value) })} />
          </div>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setReflectionOpen(false)}>Cancel</Button>
            <Button type="submit" loading={reflectionMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={trackingOpen} onClose={() => setTrackingOpen(false)} title="Configure Tracking">
        <form onSubmit={(e) => { e.preventDefault(); trackingMutation.mutate(trackingForm); }} className="space-y-4">
          <Select
            label="Tracking type"
            value={trackingForm.trackingType}
            onChange={(e) => setTrackingForm({ ...trackingForm, trackingType: e.target.value as TrackingType })}
            options={[
              { value: 'BOOLEAN', label: 'Yes / No (did it)' },
              { value: 'NUMERIC', label: 'Numeric (amount vs target)' },
              { value: 'RATING', label: 'Rating (1-5)' },
              { value: 'TEXT', label: 'Text note' },
            ]}
          />
          <Select
            label="Frequency"
            value={trackingForm.frequency ?? 'DAILY'}
            onChange={(e) => setTrackingForm({ ...trackingForm, frequency: e.target.value as Frequency })}
            options={[
              { value: 'DAILY', label: 'Daily' },
              { value: 'WEEKLY', label: 'Weekly' },
              { value: 'MONTHLY', label: 'Monthly' },
            ]}
          />
          {trackingForm.trackingType === 'NUMERIC' && (
            <div className="grid grid-cols-2 gap-4">
              <Input
                label="Target value"
                type="number"
                value={trackingForm.targetNumericValue ?? ''}
                onChange={(e) => setTrackingForm({ ...trackingForm, targetNumericValue: numOrUndef(e.target.value) })}
              />
              <Input
                label="Unit"
                value={trackingForm.targetUnit ?? ''}
                placeholder="e.g. liters"
                onChange={(e) => setTrackingForm({ ...trackingForm, targetUnit: e.target.value })}
              />
            </div>
          )}
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={trackingForm.requiredDaily ?? false}
              onChange={(e) => setTrackingForm({ ...trackingForm, requiredDaily: e.target.checked })}
              className="w-4 h-4 rounded"
            />
            <span className="text-sm font-medium text-fg-muted">Required daily</span>
          </label>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setTrackingOpen(false)}>Cancel</Button>
            <Button type="submit" loading={trackingMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>
    </PageContainer>
  );
};

export default UpgradeDetailsPage;
