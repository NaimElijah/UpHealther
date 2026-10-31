import React, { useId, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createReminder, deleteReminder, updateReminder } from '../../api/reminders';
import { toApiError } from '../../api/apiError';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Badge from '../ui/Badge';
import ErrorState from '../ui/ErrorState';
import { refusalMessage } from './formInput';
import { remindersQuery, type SectionRead } from './upgradeDetailsQueries';
import type { CreateReminderRequest, Reminder } from '../../types';
import { useFocusWhenReady } from '../../hooks/useFocusWhenReady';

/**
 * Day tokens for the reminder day picker, in week order.
 *
 * Three-letter and upper case because that is the form the API stores; selecting none means the
 * reminder fires every day.
 */
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/**
 * Each day's name in full, which is its button's name: the button shows one letter, and "T" and "S"
 * each stand for two days. The letter is the name's first, so the visible label is inside it (2.5.3).
 */
const DAY_NAMES: Record<string, string> = {
  MON: 'Monday',
  TUE: 'Tuesday',
  WED: 'Wednesday',
  THU: 'Thursday',
  FRI: 'Friday',
  SAT: 'Saturday',
  SUN: 'Sunday',
};

/** Where the reminder form's time starts, and returns to once a reminder is added or rescheduled. */
const DEFAULT_REMINDER_TIME = '09:00';

/** A reminder's time as the form and its controls show it: `HH:mm`, whether or not the API sent seconds. */
const reminderClock = (reminder: Reminder): string => reminder.reminderTime.slice(0, 5);

/** One reminder's id and the whole schedule to store for it: an update replaces the time and the days. */
interface ReminderChange {
  reminderId: string;
  req: CreateReminderRequest;
}

/**
 * @param upgradeId the upgrade whose reminders these are
 * @param read      the page's read of its reminders
 */
interface Props {
  upgradeId: string;
  read: SectionRead<Reminder[]>;
}

/**
 * An upgrade's reminders (FR-26): the list, with each one's Edit, Disable or Enable, and Remove, and the
 * form below it, which adds a reminder or reschedules the one being edited.
 *
 * Every write refreshes the reminders alone, since nothing else shows them. Only one write runs at a
 * time, for the reason given beside `reminderWriteInFlight`.
 */
const UpgradeReminders: React.FC<Props> = ({ upgradeId, read }) => {
  const qc = useQueryClient();
  const { data: reminders = [] } = read;
  const remindersKey = remindersQuery(upgradeId).queryKey;

  const [reminderTime, setReminderTime] = useState(DEFAULT_REMINDER_TIME);
  const [reminderDays, setReminderDays] = useState<string[]>([]);
  // The reminder the form below is rescheduling, or none while it adds a new one.
  const [editingReminderId, setEditingReminderId] = useState<string | null>(null);
  const timeField = useRef<HTMLInputElement>(null);
  const daysLabel = useId();
  const focusWhenReady = useFocusWhenReady();
  const toggleDay = (d: string) =>
    setReminderDays((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d]));

  const createReminderMut = useMutation({
    mutationFn: () => createReminder(upgradeId, { reminderTime, daysOfWeek: reminderDays, enabled: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: remindersKey }),
  });
  const deleteReminderMut = useMutation({
    mutationFn: (reminderId: string) => deleteReminder(reminderId),
    onSuccess: () => qc.invalidateQueries({ queryKey: remindersKey }),
  });
  // Two mutations over one endpoint, because their refusals belong in different places: a reschedule's
  // beside the form that sent it, a switch's beside the list it was pressed in (FR-26, NFR-30).
  const rescheduleReminderMut = useMutation({
    mutationFn: ({ reminderId, req }: ReminderChange) => updateReminder(reminderId, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: remindersKey }),
  });
  const switchReminderMut = useMutation({
    mutationFn: ({ reminderId, req }: ReminderChange) => updateReminder(reminderId, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: remindersKey }),
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

  /** Puts the form's time and days back to their defaults. */
  const resetReminderForm = () => {
    setReminderTime(DEFAULT_REMINDER_TIME);
    setReminderDays([]);
  };

  /** Puts the form back to adding a reminder, from its defaults. */
  const stopEditingReminder = () => {
    setEditingReminderId(null);
    resetReminderForm();
  };

  const submitReminder = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingReminderId) {
      createReminderMut.mutate(undefined, { onSuccess: resetReminderForm });
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

  return (
    <Card header={`Reminders (${reminders.length})`}>
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
                  type="button"
                  onClick={(e) => {
                    const pressed = e.currentTarget;
                    deleteReminderMut.mutate(r.id, {
                      onSuccess: () => {
                        if (editingReminderId === r.id) stopEditingReminder();
                        // The row goes, and this button with it, so focus goes to the form below, the
                        // stable control of this section, rather than falling to <body>.
                        focusWhenReady(() => (pressed.isConnected ? undefined : timeField.current));
                      },
                    });
                  }}
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
          ref={timeField}
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
          <span id={daysLabel} className="text-sm font-medium text-fg-muted block mb-1">
            Days <span className="text-fg-faint">(none = every day)</span>
          </span>
          {/* Toggle buttons in a named group. A chosen day has a ring as well as its fill, so the choice is
              not told by colour alone, and the ring is far above 3:1 in both themes (WCAG 1.4.1, 1.4.11). */}
          <div role="group" aria-labelledby={daysLabel} className="flex gap-1">
            {DAYS.map((d) => (
              <button
                key={d}
                type="button"
                aria-label={DAY_NAMES[d]}
                aria-pressed={reminderDays.includes(d)}
                onClick={() => toggleDay(d)}
                className={`w-9 h-9 rounded-lg border-2 text-xs font-medium transition-colors ${reminderDays.includes(d) ? 'bg-brand text-fg-inverted border-fg' : 'bg-muted text-fg-subtle border-transparent hover:bg-muted-strong'}`}
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
  );
};

export default UpgradeReminders;
