import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { performUpgradeAction, rescheduleUpgrade, updateUpgrade } from '../../api/upgrades';
import { getHealthAreas } from '../../api/healthAreas';
import { toApiError, toFormMessage } from '../../api/apiError';
import { isFourDigitYearDate, todayLocal } from '../../lib/localDate';
import { refreshUpgradeViews } from '../../lib/upgradeViews';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import Input from '../ui/Input';
import ErrorState from '../ui/ErrorState';
import UpgradeFormFields from './UpgradeFormFields';
import type { CreateUpgradeRequest, HealthUpgrade, UpgradeStatus } from '../../types';

/**
 * The fields an edit replaces, as the upgrade holds them now.
 *
 * Every one of them, because the API replaces the whole set and stores a missing field as empty. The
 * planned start is not among them: after creation it belongs to planning and rescheduling, and the API
 * ignores it on an edit.
 */
const editableFields = (upgrade: HealthUpgrade): CreateUpgradeRequest => ({
  title: upgrade.title,
  description: upgrade.description,
  type: upgrade.type,
  difficulty: upgrade.difficulty,
  areaId: upgrade.areaId,
  motivation: upgrade.motivation,
  successCriteria: upgrade.successCriteria,
  targetEndDate: upgrade.targetEndDate,
});

/** The states an upgrade can be abandoned from: every one that is not final (BR-2). */
const ABANDONABLE: readonly UpgradeStatus[] = ['IDEA', 'PLANNED', 'ACTIVE', 'PAUSED'];

/** The one state that cannot be rescheduled: COMPLETED is terminal (BR-3). */
const UNRESCHEDULABLE: UpgradeStatus = 'COMPLETED';

/** What the form itself refuses before sending, by field. */
interface FormCheck {
  title?: string;
  targetEnd?: string;
}

/** @param upgrade the upgrade its page shows, as last fetched */
interface Props {
  upgrade: HealthUpgrade;
}

/**
 * What can be done to an upgrade as a whole from its own page (#89): edit its descriptive fields, in any
 * state (FR-13), abandon it from any state that is not final, and reschedule it from any state but
 * COMPLETED (FR-12). Rescheduling an abandoned upgrade is also the way back from abandoning it (BR-2).
 *
 * Abandoning asks first. An abandoned upgrade leaves every list, so a slip would look as though the
 * upgrade had vanished.
 *
 * Separate from the page because each action brings a dialog and a mutation of its own, and the page
 * already holds four of each. A write here refreshes every view of upgrades (ADR-029), the page's own
 * included.
 */
const UpgradeActions: React.FC<Props> = ({ upgrade }) => {
  const qc = useQueryClient();
  const { data: areas = [] } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const [editOpen, setEditOpen] = useState(false);
  const [editForm, setEditForm] = useState<CreateUpgradeRequest>(() => editableFields(upgrade));
  const [editCheck, setEditCheck] = useState<FormCheck>({});

  // Closing the dialog is passed to each `mutate` call rather than set here, for the reason the page
  // gives for its own dialogs: only a call's own callbacks stop when the opener resets the mutation.
  const editMutation = useMutation({
    mutationFn: (req: CreateUpgradeRequest) => updateUpgrade(upgrade.id, req),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  const [abandonOpen, setAbandonOpen] = useState(false);
  const abandonMutation = useMutation({
    mutationFn: () => performUpgradeAction(upgrade.id, 'ABANDONED'),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** Asks before abandoning, without a refusal left over from an earlier attempt. */
  const openAbandon = () => {
    abandonMutation.reset();
    setAbandonOpen(true);
  };

  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  const [newDate, setNewDate] = useState('');
  const [dateCheck, setDateCheck] = useState('');
  const rescheduleMutation = useMutation({
    mutationFn: (date: string) => rescheduleUpgrade(upgrade.id, date),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** Opens on the planned start, or on the user's today when there is none. */
  const openReschedule = () => {
    setNewDate(upgrade.plannedStartDate ?? todayLocal());
    setDateCheck('');
    rescheduleMutation.reset();
    setRescheduleOpen(true);
  };

  const submitReschedule = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newDate) { setDateCheck('Pick a start date.'); return; }
    if (!isFourDigitYearDate(newDate)) { setDateCheck('Pick a date with a four-digit year.'); return; }
    setDateCheck('');
    rescheduleMutation.mutate(newDate, { onSuccess: () => setRescheduleOpen(false) });
  };

  const rescheduleRefusal = rescheduleMutation.error ? toFormMessage(toApiError(rescheduleMutation.error), 'newDate') : undefined;

  /** Starts an edit from what the upgrade holds now, with nothing left over from an earlier attempt. */
  const openEdit = () => {
    setEditForm(editableFields(upgrade));
    setEditCheck({});
    editMutation.reset();
    setEditOpen(true);
  };

  const submitEdit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editForm.title.trim()) { setEditCheck({ title: 'Title is required.' }); return; }
    if (editForm.targetEndDate && !isFourDigitYearDate(editForm.targetEndDate)) {
      setEditCheck({ targetEnd: 'Pick a date with a four-digit year.' });
      return;
    }
    setEditCheck({});
    editMutation.mutate(editForm, { onSuccess: () => setEditOpen(false) });
  };

  const editRefusal = editMutation.error ? toFormMessage(toApiError(editMutation.error), 'title') : undefined;

  return (
    <>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-line-subtle pt-4">
        <Button size="sm" variant="secondary" onClick={openEdit}>Edit Upgrade</Button>
        {upgrade.status !== UNRESCHEDULABLE && (
          <Button size="sm" variant="secondary" onClick={openReschedule}>Reschedule</Button>
        )}
        {ABANDONABLE.includes(upgrade.status) && (
          <Button size="sm" variant="secondary" onClick={openAbandon}>Abandon</Button>
        )}
      </div>

      <Modal isOpen={editOpen} onClose={() => setEditOpen(false)} title="Edit Upgrade">
        <form onSubmit={submitEdit} className="space-y-4">
          <UpgradeFormFields
            value={editForm}
            onChange={setEditForm}
            areas={areas}
            titleError={editCheck.title ?? editRefusal}
            targetEndError={editCheck.targetEnd}
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setEditOpen(false)}>Cancel</Button>
            <Button type="submit" loading={editMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={rescheduleOpen} onClose={() => setRescheduleOpen(false)} title="Reschedule Upgrade">
        <form onSubmit={submitReschedule} className="space-y-4">
          <p className="text-fg-subtle">
            {upgrade.status === 'ABANDONED'
              ? 'When do you want to start again? The upgrade goes back to Planned.'
              : 'When do you want to start instead?'}
          </p>
          <Input
            label="New start date"
            type="date"
            value={newDate}
            onChange={(e) => setNewDate(e.target.value)}
            error={dateCheck || rescheduleRefusal}
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setRescheduleOpen(false)}>Cancel</Button>
            <Button type="submit" loading={rescheduleMutation.isPending}>Reschedule</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={abandonOpen} onClose={() => setAbandonOpen(false)} title="Abandon Upgrade">
        <p className="text-fg-subtle mb-4">
          Abandon <span className="font-medium text-fg break-words">{upgrade.title}</span>? It leaves your lists of
          ideas, planned and active upgrades. Reschedule it from this page to plan it again.
        </p>
        {abandonMutation.error && (
          <div className="mb-4">
            <ErrorState inline title="The upgrade was not abandoned." error={toApiError(abandonMutation.error)} />
          </div>
        )}
        <div className="flex gap-2 justify-end">
          <Button variant="secondary" onClick={() => setAbandonOpen(false)}>Cancel</Button>
          <Button
            variant="danger"
            loading={abandonMutation.isPending}
            onClick={() => abandonMutation.mutate(undefined, { onSuccess: () => setAbandonOpen(false) })}
          >
            Abandon
          </Button>
        </div>
      </Modal>
    </>
  );
};

export default UpgradeActions;
