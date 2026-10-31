import React, { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createProgress } from '../../api/progress';
import { latestProgressDate, parseLocalDate, todayLocal } from '../../lib/localDate';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Input from '../ui/Input';
import Modal from '../ui/Modal';
import Badge from '../ui/Badge';
import { numOrUndef, refusalMessage } from './formInput';
import { progressQuery, streakQuery, type SectionRead } from './upgradeDetailsQueries';
import type { CreateProgressRequest, HealthUpgrade, ProgressEntry } from '../../types';

/** What the Log Progress form holds: the request without its URL-only id, for a date it always has. */
type ProgressForm = Omit<CreateProgressRequest, 'upgradeId'> & { date: string };

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

/**
 * @param upgradeId      the upgrade the entries are logged against
 * @param read           the page's read of the upgrade's progress history
 * @param trackingConfig how the upgrade is tracked, which decides the form's input; none means yes or no
 */
interface Props {
  upgradeId: string;
  read: SectionRead<ProgressEntry[]>;
  trackingConfig: HealthUpgrade['trackingConfig'];
}

/**
 * An upgrade's progress history (FR-20), newest first as the API returns it, and the **Log Progress**
 * dialog, whose input follows the tracking configuration.
 *
 * A logged entry refreshes this history and the streak (`UpgradeStreak`), and nothing else: the
 * dashboard and the progress history page stay as they were for up to the cache's thirty seconds. That
 * is #192, a known bug, left as it is here.
 */
const UpgradeProgress: React.FC<Props> = ({ upgradeId, read, trackingConfig }) => {
  const qc = useQueryClient();
  const { data: progress = [] } = read;

  const [progressOpen, setProgressOpen] = useState(false);
  const [progressForm, setProgressForm] = useState(newProgressForm);
  // The last date the API accepts, read when the dialog opens as its default date is, so the field
  // offers no day the server would refuse (FR-18).
  const [latestDate, setLatestDate] = useState(latestProgressDate);

  // Closing the dialog is passed to each `mutate` call rather than set here, because only a call's own
  // callbacks stop when an opener below resets the mutation; these run for every save that succeeds.
  const progressMutation = useMutation({
    mutationFn: (body: Omit<CreateProgressRequest, 'upgradeId'>) => createProgress({ ...body, upgradeId }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: progressQuery(upgradeId).queryKey });
      qc.invalidateQueries({ queryKey: streakQuery(upgradeId).queryKey });
    },
  });

  /**
   * Starts a new progress entry: fresh defaults, and no tie to an earlier save (#119).
   *
   * A save left in flight by Cancel would otherwise close this dialog when it answers and show its
   * spinner on this one's Save. Resetting detaches it; it still refreshes the history when it lands.
   */
  const openProgress = () => {
    setProgressForm(newProgressForm());
    setLatestDate(latestProgressDate());
    progressMutation.reset();
    setProgressOpen(true);
  };

  return (
    <>
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
            max={latestDate}
            value={progressForm.date}
            onChange={(e) => setProgressForm({ ...progressForm, date: e.target.value })}
            error={refusalMessage(progressMutation.error, 'date')}
          />
          {(!trackingConfig || trackingConfig.trackingType === 'BOOLEAN') && (
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
          {trackingConfig?.trackingType === 'NUMERIC' && (
            <Input
              label={`Value (${trackingConfig.targetUnit ?? ''})`}
              type="number"
              value={progressForm.numericValue ?? ''}
              onChange={(e) => setProgressForm({ ...progressForm, numericValue: numOrUndef(e.target.value) })}
            />
          )}
          {trackingConfig?.trackingType === 'RATING' && (
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
    </>
  );
};

export default UpgradeProgress;
