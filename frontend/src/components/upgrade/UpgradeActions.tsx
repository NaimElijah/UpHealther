import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { updateUpgrade } from '../../api/upgrades';
import { getHealthAreas } from '../../api/healthAreas';
import { toApiError, toFormMessage } from '../../api/apiError';
import { isFourDigitYearDate } from '../../lib/localDate';
import { refreshUpgradeViews } from '../../lib/upgradeViews';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import UpgradeFormFields from './UpgradeFormFields';
import type { CreateUpgradeRequest, HealthUpgrade } from '../../types';

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
 * state (FR-13).
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
    </>
  );
};

export default UpgradeActions;
