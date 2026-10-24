import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgradeById } from '../api/upgrades';
import { getProgressByUpgrade, createProgress, getStreak } from '../api/progress';
import { getReflectionsByUpgrade, createReflection } from '../api/reflections';
import { saveTrackingConfig } from '../api/trackingConfig';
import { getReminders, createReminder, deleteReminder, updateReminder } from '../api/reminders';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import Card from '../components/ui/Card';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import Select from '../components/ui/Select';
import Modal from '../components/ui/Modal';
import UpgradeStatusBadge from '../components/upgrade/UpgradeStatusBadge';
import UpgradeTypeBadge from '../components/upgrade/UpgradeTypeBadge';
import UpgradeActions from '../components/upgrade/UpgradeActions';
import Badge from '../components/ui/Badge';
import { difficultyBadgeVariant } from '../components/upgrade/upgradeMeta';
import type {
  CreateProgressRequest,
  CreateReflectionRequest,
  CreateReminderRequest,
  HealthUpgrade,
  Reminder,
  SaveTrackingConfigRequest,
  TrackingType,
  Frequency,
} from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError, toFormMessage } from '../api/apiError';
import { parseLocalDate, todayLocal } from '../lib/localDate';
import { refreshUpgradeViews } from '../lib/upgradeViews';

/** What the Log Progress form holds: the request without its URL-only id, for a date it always has. */
type ProgressForm = Omit<CreateProgressRequest, 'upgradeId'> & { date: string };

/** What the Add Reflection form holds: the request, for a date it always has. */
type ReflectionForm = CreateReflectionRequest & { date: string };

/**
 * A blank progress entry for today, set each time **Log Progress** opens (#119).
 *
 * Opening is when a new entry starts. Set only at mount, a page left open past midnight offered
 * yesterday, and a second entry in one visit started from the last one's values.
 */
const newProgressForm = (): ProgressForm => ({
  date: todayLocal(),
  completed: false,
  note: '',
});

/** Where a new reflection's difficulty and benefit start: the middle of their 1-5 scale. */
const DEFAULT_REFLECTION_RATING = 3;

/** A blank reflection for today, set each time **Add Reflection** opens; see {@link newProgressForm}. */
const newReflectionForm = (): ReflectionForm => ({
  date: todayLocal(),
  whatWorked: '',
  whatDidNotWork: '',
  nextAdjustment: '',
  difficultyRating: DEFAULT_REFLECTION_RATING,
  benefitRating: DEFAULT_REFLECTION_RATING,
});

/**
 * Day tokens for the reminder day picker, in week order.
 *
 * Three-letter and upper case because that is the form the API stores; selecting none means the
 * reminder fires every day.
 */
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/** Where the reminder form's time starts, and returns to once a reminder is added or rescheduled. */
const DEFAULT_REMINDER_TIME = '09:00';

/** A select's choices, in the order a label map lists them. */
const optionsOf = <T extends string>(labels: Record<T, string>): { value: T; label: string }[] =>
  (Object.keys(labels) as T[]).map((value) => ({ value, label: labels[value] }));

/**
 * What the configuration form calls each tracking type. Keyed by the generated union, so a type the API
 * adds is a compile error here rather than an option the form lacks: `offered` would then read a saved
 * one as the default, and a save would overwrite it.
 */
const TRACKING_TYPE_LABELS: Record<TrackingType, string> = {
  BOOLEAN: 'Yes / No (did it)',
  NUMERIC: 'Numeric (amount vs target)',
  RATING: 'Rating (1-5)',
  TEXT: 'Text note',
};

/** What the configuration form calls each frequency; total over the union for the same reason. */
const FREQUENCY_LABELS: Record<Frequency, string> = {
  DAILY: 'Daily',
  WEEKLY: 'Weekly',
  MONTHLY: 'Monthly',
};

const TRACKING_TYPE_OPTIONS = optionsOf(TRACKING_TYPE_LABELS);
const FREQUENCY_OPTIONS = optionsOf(FREQUENCY_LABELS);

/**
 * The option a saved value names, or nothing when it names none of them.
 *
 * The upgrade carries its tracking configuration with the type and frequency as plain strings, since
 * the upgrade context restates them rather than borrowing the tracking context's enums. So a value is
 * matched against what the form offers rather than cast to it.
 */
const offered = <T extends string>(options: { value: T }[], saved: string | null | undefined): T | undefined =>
  options.find((option) => option.value === saved)?.value;

/** A reminder's time as the form and its controls show it: `HH:mm`, whether or not the API sent seconds. */
const reminderClock = (reminder: Reminder): string => reminder.reminderTime.slice(0, 5);

/** One reminder's id and the whole schedule to store for it: an update replaces the time and the days. */
interface ReminderChange {
  reminderId: string;
  req: CreateReminderRequest;
}

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
 * Why a form's last save was refused, for its main field (NFR-30); nothing while there is no refusal.
 *
 * Read from the mutation itself rather than copied into state: a new save clears it, and so does an
 * opener's `reset()`, which also detaches a save still in flight so its refusal cannot reach the next
 * dialog (#119).
 *
 * @param error   the mutation's `error`
 * @param primary the form's main field, whose own message is shown alone when it is the one refused
 */
const refusalMessage = (error: Error | null, primary: string): string | undefined =>
  error ? toFormMessage(toApiError(error), primary) : undefined;

/**
 * Everything about one upgrade: its details, progress history, streak, reflections, reminders and
 * tracking configuration.
 *
 * The busiest page in the app, and the only place several of these can be edited at all. Each concern
 * is its own query and its own mutation, so saving a reflection does not refetch the progress history;
 * what they share is the upgrade id from the route.
 */
const UpgradeDetails: React.FC = () => {
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

  const [progressForm, setProgressForm] = useState(newProgressForm);
  const [reflectionForm, setReflectionForm] = useState(newReflectionForm);

  const [reminderTime, setReminderTime] = useState(DEFAULT_REMINDER_TIME);
  const [reminderDays, setReminderDays] = useState<string[]>([]);
  // The reminder the form below is rescheduling, or none while it adds a new one.
  const [editingReminderId, setEditingReminderId] = useState<string | null>(null);
  const toggleDay = (d: string) =>
    setReminderDays((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d]));

  const {
    data: upgrade,
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrade', id], queryFn: () => getUpgradeById(id!), enabled: !!id });
  const { data: progress = [] } = useQuery({
    queryKey: ['progress', id],
    queryFn: () => getProgressByUpgrade(id!),
    enabled: !!id,
  });
  const { data: reflections = [] } = useQuery({
    queryKey: ['reflections', id],
    queryFn: () => getReflectionsByUpgrade(id!),
    enabled: !!id,
  });
  const { data: streak = { current: 0, longest: 0 } } = useQuery({
    queryKey: ['streak', id],
    queryFn: () => getStreak(id!),
    enabled: !!id,
  });
  const { data: reminders = [] } = useQuery({
    queryKey: ['reminders', id],
    queryFn: () => getReminders(id!),
    enabled: !!id,
  });

  const createReminderMut = useMutation({
    mutationFn: () => createReminder(id!, { reminderTime, daysOfWeek: reminderDays, enabled: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reminders', id] }),
  });
  const deleteReminderMut = useMutation({
    mutationFn: (reminderId: string) => deleteReminder(reminderId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reminders', id] }),
  });
  // Two mutations over one endpoint, because their refusals belong in different places: a reschedule's
  // beside the form that sent it, a switch's beside the list it was pressed in (FR-26, NFR-30).
  const rescheduleReminderMut = useMutation({
    mutationFn: ({ reminderId, req }: ReminderChange) => updateReminder(reminderId, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reminders', id] }),
  });
  const switchReminderMut = useMutation({
    mutationFn: ({ reminderId, req }: ReminderChange) => updateReminder(reminderId, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reminders', id] }),
  });

  // One write to the reminders at a time. A switch resends the schedule its row shows, and the form
  // edits from a row, so either one acting while another write is in flight works from a schedule that is
  // about to change, and the later answer silently undoes the earlier one. It also keeps the form's mode
  // fixed until an answer lands, so a removal's callback still sees the reminder being edited.
  const reminderWriteInFlight =
    createReminderMut.isPending ||
    rescheduleReminderMut.isPending ||
    switchReminderMut.isPending ||
    deleteReminderMut.isPending;

  /**
   * Loads a reminder into the form below, which then saves it rather than adding a new one. An add's
   * refusal goes with the add, so it does not reappear when the edit is cancelled.
   */
  const editReminder = (reminder: Reminder) => {
    setReminderTime(reminderClock(reminder));
    setReminderDays(reminder.daysOfWeek);
    rescheduleReminderMut.reset();
    createReminderMut.reset();
    setEditingReminderId(reminder.id);
  };

  /** Puts the form back to adding a reminder, from its defaults. */
  const stopEditingReminder = () => {
    setEditingReminderId(null);
    setReminderTime(DEFAULT_REMINDER_TIME);
    setReminderDays([]);
  };

  const submitReminder = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingReminderId) {
      createReminderMut.mutate(undefined, { onSuccess: () => setReminderDays([]) });
      return;
    }
    // `enabled` is left out, so a disabled reminder stays disabled when its schedule moves.
    rescheduleReminderMut.mutate(
      { reminderId: editingReminderId, req: { reminderTime, daysOfWeek: reminderDays } },
      { onSuccess: stopEditingReminder },
    );
  };

  /** Switches a reminder off or back on, keeping its schedule: the update replaces the time and days too. */
  const switchReminder = (reminder: Reminder) =>
    switchReminderMut.mutate({
      reminderId: reminder.id,
      req: { reminderTime: reminder.reminderTime, daysOfWeek: reminder.daysOfWeek, enabled: !reminder.enabled },
    });

  // Closing the dialog is passed to each `mutate` call rather than set here, because only a call's own
  // callbacks stop when an opener below resets the mutation; these run for every save that succeeds.
  const progressMutation = useMutation({
    mutationFn: (body: Omit<CreateProgressRequest, 'upgradeId'>) => createProgress({ ...body, upgradeId: id! }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['progress', id] });
      qc.invalidateQueries({ queryKey: ['streak', id] });
    },
  });

  const reflectionMutation = useMutation({
    mutationFn: (body: Omit<CreateReflectionRequest, 'upgradeId'>) => createReflection(id!, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['reflections', id] }),
  });

  /**
   * Starts a new progress entry: fresh defaults, and no tie to an earlier save (#119).
   *
   * A save left in flight by Cancel would otherwise close this dialog when it answers and show its
   * spinner on this one's Save. Resetting detaches it; it still refreshes the history when it lands.
   */
  const openProgress = () => {
    setProgressForm(newProgressForm());
    progressMutation.reset();
    setProgressOpen(true);
  };

  /** Starts a new reflection, on the same terms as {@link openProgress}. */
  const openReflection = () => {
    setReflectionForm(newReflectionForm());
    reflectionMutation.reset();
    setReflectionOpen(true);
  };

  // Every view of upgrades, not just this one: an upgrade carries its tracking configuration (FR-15), and
  // the daily check-in picks each entry's input from it (#189).
  const trackingMutation = useMutation({
    mutationFn: (req: SaveTrackingConfigRequest) => saveTrackingConfig(id!, req),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** Edits the tracking configuration from what is saved, on the same terms as {@link openProgress}. */
  const openTracking = (saved: HealthUpgrade['trackingConfig']) => {
    setTrackingForm({
      trackingType: offered(TRACKING_TYPE_OPTIONS, saved?.trackingType) ?? 'BOOLEAN',
      frequency: offered(FREQUENCY_OPTIONS, saved?.frequency) ?? 'DAILY',
      targetNumericValue: saved?.targetNumericValue,
      targetUnit: saved?.targetUnit,
      requiredDaily: saved?.requiredDaily ?? true,
    });
    trackingMutation.reset();
    setTrackingOpen(true);
  };

  if (isLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (error || !upgrade)
    return <ErrorState title="Could not load this upgrade." error={error ? toApiError(error) : undefined} />;

  return (
    <PageContainer width="narrow" className="space-y-6">
      <button onClick={() => navigate(-1)} className="text-sm text-brand-fg hover:underline">
        ← Back
      </button>

      <Card>
        <div className="flex flex-wrap gap-2 mb-3">
          <UpgradeStatusBadge status={upgrade.status} />
          <UpgradeTypeBadge type={upgrade.type} />
          <Badge variant={difficultyBadgeVariant(upgrade.difficulty)}>{upgrade.difficulty}</Badge>
        </div>
        <h1 className="text-2xl font-bold text-fg break-words">{upgrade.title}</h1>
        {upgrade.description && <p className="text-fg-subtle mt-2 break-words">{upgrade.description}</p>}
        {upgrade.motivation && (
          <div className="mt-4 bg-brand-soft rounded-lg p-3">
            <p className="text-sm text-brand-fg break-words">
              <strong>Motivation:</strong> {upgrade.motivation}
            </p>
          </div>
        )}
        {upgrade.successCriteria && (
          <div className="mt-2 bg-success-soft rounded-lg p-3">
            <p className="text-sm text-success-fg break-words">
              <strong>Success Criteria:</strong> {upgrade.successCriteria}
            </p>
          </div>
        )}
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm text-fg-subtle">
          {upgrade.plannedStartDate && (
            <div>
              <span className="font-medium">Planned Start:</span>{' '}
              {parseLocalDate(upgrade.plannedStartDate).toLocaleDateString()}
            </div>
          )}
          {upgrade.actualStartDate && (
            <div>
              <span className="font-medium">Actual Start:</span>{' '}
              {parseLocalDate(upgrade.actualStartDate).toLocaleDateString()}
            </div>
          )}
          {upgrade.targetEndDate && (
            <div>
              <span className="font-medium">Target End:</span>{' '}
              {parseLocalDate(upgrade.targetEndDate).toLocaleDateString()}
            </div>
          )}
        </div>
        <UpgradeActions upgrade={upgrade} />
      </Card>

      {streak.current > 0 && (
        <div className="bg-streak-soft border border-streak-line rounded-xl p-4 flex items-center gap-3">
          <span className="text-3xl">🔥</span>
          <div>
            <p className="font-semibold text-streak-fg">{streak.current}-day streak!</p>
            <p className="text-sm text-streak-fg">
              Longest streak: {streak.longest} day{streak.longest === 1 ? '' : 's'}. Keep it up!
            </p>
          </div>
        </div>
      )}

      <Card
        header={
          <div className="flex items-center justify-between">
            <span>Tracking Configuration</span>
            <Button size="sm" variant="secondary" onClick={() => openTracking(upgrade.trackingConfig)}>
              {upgrade.trackingConfig ? 'Edit' : 'Configure'}
            </Button>
          </div>
        }
      >
        {upgrade.trackingConfig ? (
          <div className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <span className="text-fg-subtle">Type:</span>{' '}
              <span className="font-medium">{upgrade.trackingConfig.trackingType}</span>
            </div>
            <div>
              <span className="text-fg-subtle">Frequency:</span>{' '}
              <span className="font-medium">{upgrade.trackingConfig.frequency}</span>
            </div>
            {upgrade.trackingConfig.targetNumericValue != null && (
              <div>
                <span className="text-fg-subtle">Target:</span>{' '}
                <span className="font-medium">
                  {upgrade.trackingConfig.targetNumericValue} {upgrade.trackingConfig.targetUnit}
                </span>
              </div>
            )}
          </div>
        ) : (
          <p className="text-fg-subtle text-sm text-center py-4">
            No tracking configured yet. Configure how you'll track this upgrade.
          </p>
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
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => editReminder(r)}
                    disabled={reminderWriteInFlight}
                    aria-label={`Edit the ${reminderClock(r)} reminder`}
                    className="text-brand-fg hover:underline text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => switchReminder(r)}
                    disabled={reminderWriteInFlight}
                    aria-label={`${r.enabled ? 'Disable' : 'Enable'} the ${reminderClock(r)} reminder`}
                    className="text-fg-subtle hover:underline text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {r.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button
                    onClick={() =>
                      deleteReminderMut.mutate(r.id, {
                        onSuccess: () => {
                          if (editingReminderId === r.id) stopEditingReminder();
                        },
                      })
                    }
                    // A second DELETE would be answered 404, saying "not removed" while the row disappears.
                    disabled={reminderWriteInFlight}
                    className="text-danger-fg hover:underline text-xs font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    Remove
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
        {deleteReminderMut.error && (
          <div className="mb-4">
            <ErrorState inline title="The reminder was not removed." error={toApiError(deleteReminderMut.error)} />
          </div>
        )}
        {switchReminderMut.error && (
          <div className="mb-4">
            <ErrorState inline title="The reminder was not changed." error={toApiError(switchReminderMut.error)} />
          </div>
        )}
        <form onSubmit={submitReminder} className="flex flex-wrap items-end gap-3 border-t border-line-subtle pt-3">
          <Input
            label="Time"
            type="time"
            value={reminderTime}
            onChange={(e) => setReminderTime(e.target.value)}
            error={refusalMessage(
              editingReminderId ? rescheduleReminderMut.error : createReminderMut.error,
              'reminderTime',
            )}
          />
          <div>
            <label className="text-sm font-medium text-fg-muted block mb-1">
              Days <span className="text-fg-faint">(none = every day)</span>
            </label>
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
          {editingReminderId ? (
            <>
              <Button type="submit" loading={rescheduleReminderMut.isPending} disabled={reminderWriteInFlight}>
                Save reminder
              </Button>
              <Button type="button" variant="secondary" onClick={stopEditingReminder} disabled={reminderWriteInFlight}>
                Cancel
              </Button>
            </>
          ) : (
            <Button type="submit" loading={createReminderMut.isPending} disabled={reminderWriteInFlight}>
              Add reminder
            </Button>
          )}
        </form>
      </Card>

      <Card
        header={
          <div className="flex items-center justify-between">
            <span>Progress History ({progress.length})</span>
            <Button size="sm" onClick={openProgress}>
              + Log Progress
            </Button>
          </div>
        }
      >
        {progress.length === 0 ? (
          <p className="text-fg-subtle text-sm text-center py-4">No progress logged yet.</p>
        ) : (
          <div className="space-y-2 max-h-64 overflow-y-auto">
            {progress.map((p) => (
              <div key={p.id} className="flex items-center justify-between p-3 bg-sunken rounded-lg text-sm">
                <span className="text-fg-subtle">{parseLocalDate(p.date).toLocaleDateString()}</span>
                <div className="flex items-center gap-3">
                  {p.completed != null && (
                    <Badge variant={p.completed ? 'green' : 'red'}>{p.completed ? 'Done' : 'Missed'}</Badge>
                  )}
                  {p.numericValue != null && (
                    <span className="font-medium">
                      {p.numericValue} {p.unit}
                    </span>
                  )}
                  {p.rating != null && <span>⭐ {p.rating}/5</span>}
                  {p.note && <span className="text-fg-subtle italic truncate max-w-32">{p.note}</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card
        header={
          <div className="flex items-center justify-between">
            <span>Reflections ({reflections.length})</span>
            <Button size="sm" variant="secondary" onClick={openReflection}>
              + Add Reflection
            </Button>
          </div>
        }
      >
        {reflections.length === 0 ? (
          <p className="text-fg-subtle text-sm text-center py-4">No reflections yet.</p>
        ) : (
          <div className="space-y-3">
            {reflections.map((r) => (
              <div key={r.id} className="p-3 bg-sunken rounded-lg text-sm space-y-1">
                <p className="text-fg-faint">{parseLocalDate(r.date).toLocaleDateString()}</p>
                {r.whatWorked && (
                  <p>
                    <strong>✅ What worked:</strong> {r.whatWorked}
                  </p>
                )}
                {r.whatDidNotWork && (
                  <p>
                    <strong>❌ What didn't:</strong> {r.whatDidNotWork}
                  </p>
                )}
                {r.nextAdjustment && (
                  <p>
                    <strong>🔄 Next:</strong> {r.nextAdjustment}
                  </p>
                )}
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
        <form
          onSubmit={(e) => {
            e.preventDefault();
            progressMutation.mutate(progressForm, { onSuccess: () => setProgressOpen(false) });
          }}
          className="space-y-4"
        >
          <Input
            label="Date"
            type="date"
            value={progressForm.date}
            onChange={(e) => setProgressForm({ ...progressForm, date: e.target.value })}
            error={refusalMessage(progressMutation.error, 'date')}
          />
          {(!upgrade.trackingConfig || upgrade.trackingConfig.trackingType === 'BOOLEAN') && (
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={progressForm.completed ?? false}
                onChange={(e) => setProgressForm({ ...progressForm, completed: e.target.checked })}
                className="w-4 h-4 rounded"
              />
              <span className="text-sm font-medium text-fg-muted">Completed</span>
            </label>
          )}
          {upgrade.trackingConfig?.trackingType === 'NUMERIC' && (
            <Input
              label={`Value (${upgrade.trackingConfig.targetUnit ?? ''})`}
              type="number"
              value={progressForm.numericValue ?? ''}
              onChange={(e) => setProgressForm({ ...progressForm, numericValue: numOrUndef(e.target.value) })}
            />
          )}
          {upgrade.trackingConfig?.trackingType === 'RATING' && (
            <div>
              <label className="text-sm font-medium text-fg-muted">Rating (1-5)</label>
              <div className="flex gap-2 mt-2">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button
                    key={n}
                    type="button"
                    onClick={() => setProgressForm({ ...progressForm, rating: n })}
                    className={`w-10 h-10 rounded-full text-sm font-semibold transition-colors ${progressForm.rating === n ? 'bg-brand text-fg-inverted' : 'bg-muted text-fg-muted hover:bg-muted-strong'}`}
                  >
                    {n}
                  </button>
                ))}
              </div>
            </div>
          )}
          <Input
            label="Note"
            value={progressForm.note ?? ''}
            onChange={(e) => setProgressForm({ ...progressForm, note: e.target.value })}
            placeholder="Optional note"
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setProgressOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={progressMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={reflectionOpen} onClose={() => setReflectionOpen(false)} title="Add Reflection">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            reflectionMutation.mutate(reflectionForm, { onSuccess: () => setReflectionOpen(false) });
          }}
          className="space-y-4"
        >
          <Input
            label="Date"
            type="date"
            value={reflectionForm.date}
            onChange={(e) => setReflectionForm({ ...reflectionForm, date: e.target.value })}
            error={refusalMessage(reflectionMutation.error, 'date')}
          />
          <div>
            <label htmlFor="reflection-what-worked" className="text-sm font-medium text-fg-muted">
              What worked?
            </label>
            <textarea
              id="reflection-what-worked"
              className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.whatWorked ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, whatWorked: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="reflection-what-did-not-work" className="text-sm font-medium text-fg-muted">
              What didn't work?
            </label>
            <textarea
              id="reflection-what-did-not-work"
              className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.whatDidNotWork ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, whatDidNotWork: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="reflection-next-adjustment" className="text-sm font-medium text-fg-muted">
              Next adjustment?
            </label>
            <textarea
              id="reflection-next-adjustment"
              className="w-full mt-1 rounded-lg border border-line-strong px-3 py-2 text-sm focus:outline-none focus:ring-2"
              rows={2}
              value={reflectionForm.nextAdjustment ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, nextAdjustment: e.target.value })}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Difficulty (1-5)"
              type="number"
              min={1}
              max={5}
              value={reflectionForm.difficultyRating ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, difficultyRating: intOrUndef(e.target.value) })}
            />
            <Input
              label="Benefit (1-5)"
              type="number"
              min={1}
              max={5}
              value={reflectionForm.benefitRating ?? ''}
              onChange={(e) => setReflectionForm({ ...reflectionForm, benefitRating: intOrUndef(e.target.value) })}
            />
          </div>
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setReflectionOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={reflectionMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={trackingOpen} onClose={() => setTrackingOpen(false)} title="Configure Tracking">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            trackingMutation.mutate(trackingForm, { onSuccess: () => setTrackingOpen(false) });
          }}
          className="space-y-4"
        >
          <Select
            label="Tracking type"
            value={trackingForm.trackingType}
            onChange={(e) => setTrackingForm({ ...trackingForm, trackingType: e.target.value as TrackingType })}
            error={refusalMessage(trackingMutation.error, 'trackingType')}
            options={TRACKING_TYPE_OPTIONS}
          />
          <Select
            label="Frequency"
            value={trackingForm.frequency ?? 'DAILY'}
            onChange={(e) => setTrackingForm({ ...trackingForm, frequency: e.target.value as Frequency })}
            options={FREQUENCY_OPTIONS}
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
            <Button variant="secondary" type="button" onClick={() => setTrackingOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={trackingMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>
    </PageContainer>
  );
};

/**
 * The upgrade page as the router mounts it: one instance per upgrade.
 *
 * React Router keeps an element mounted when only `:id` changes, which is how the notification bell, a
 * toast and the notifications page move between upgrades. Every form, open dialog and in-flight save on
 * the page belongs to one upgrade, so keying on the id starts each upgrade from a fresh instance instead
 * of carrying any of them across (#116). An in-flight save then finishes against its own upgrade's
 * caches, because the instance that started it is the one whose callbacks run.
 */
const UpgradeDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  return <UpgradeDetails key={id} />;
};

export default UpgradeDetailsPage;
