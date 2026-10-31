import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteUpgrade, performUpgradeAction, rescheduleUpgrade, updateUpgrade } from '../../api/upgrades';
import { getHealthAreas } from '../../api/healthAreas';
import { toApiError, toFormMessage } from '../../api/apiError';
import { isFourDigitYearDate, todayLocal } from '../../lib/localDate';
import { refreshUpgradeViews } from '../../lib/upgradeViews';
import Button from '../ui/Button';
import Modal from '../ui/Modal';
import Input from '../ui/Input';
import ErrorState from '../ui/ErrorState';
import { holdsUnreadableText, UNFINISHED_DATE, UNFINISHED_OPTIONAL_DATE } from '../ui/unreadableInput';
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

/**
 * The states Reschedule is offered in. BR-2 also lets the API move an idea's date, but an idea is
 * *planned*: rescheduling one would set a start date and leave it an idea, listed with the ideas, until
 * Plan overwrote the date. COMPLETED is terminal (BR-3).
 */
const RESCHEDULABLE: readonly UpgradeStatus[] = ['PLANNED', 'ACTIVE', 'PAUSED', 'ABANDONED'];

/** What the reschedule dialog asks, by state: only from ABANDONED does it change more than the date. */
const rescheduleQuestion = (status: UpgradeStatus): string => {
  if (status === 'ABANDONED') return 'When do you want to start again? The upgrade goes back to Planned.';
  if (status === 'PLANNED') return 'When do you want to start instead?';
  return 'It has already started, so only its planned start date moves. Move it to when?';
};

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
 * state (FR-13), abandon it from any state that is not final, and reschedule it once it is past being an
 * idea (FR-12). Rescheduling an abandoned upgrade is also the way back from abandoning it (BR-2).
 * And delete it, in any state, with everything recorded against it (FR-14).
 *
 * Abandoning and deleting ask first. An abandoned upgrade leaves every list, so a slip would look as
 * though the upgrade had vanished; a deleted one cannot be brought back at all, and its page has nothing
 * left to show, so the dashboard opens in its place.
 *
 * Separate from the page, like the sections below it, because each action brings a dialog and a mutation
 * of its own. A write here refreshes every view of upgrades (ADR-029), the page's own included.
 */
const UpgradeActions: React.FC<Props> = ({ upgrade }) => {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { data: areas = [], error: areasError } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const [editOpen, setEditOpen] = useState(false);
  const [editForm, setEditForm] = useState<CreateUpgradeRequest>(() => editableFields(upgrade));
  const [editCheck, setEditCheck] = useState<FormCheck>({});
  const titleField = useRef<HTMLInputElement>(null);
  const targetEndField = useRef<HTMLInputElement>(null);

  // Closing the dialog is passed to each `mutate` call rather than set here, for the reason
  // `UpgradeProgress` gives for its dialog: only a call's own callbacks stop when the opener resets the
  // mutation.
  const editMutation = useMutation({
    mutationFn: (req: CreateUpgradeRequest) => updateUpgrade(upgrade.id, req),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  const [abandonOpen, setAbandonOpen] = useState(false);
  const rescheduleButton = useRef<HTMLButtonElement>(null);
  // Set by a confirmed abandon, and acted on once the dialog has closed and released the page. State,
  // not a ref: setting it has to run the effect below even when the dialog is already closed, which
  // happens when a dismissal lands before the dialog learns the abandon is in flight. It is cleared when
  // the dialog next opens, never by the effect, so a later Cancel gives focus back to its opener.
  const [focusRescheduleNext, setFocusRescheduleNext] = useState(false);

  // Abandoning removes the Abandon button that opened the dialog, so the dialog has nothing to give
  // focus back to and it would fall to <body> (FR-40). Reschedule is the way back the dialog points to,
  // and the abandoned upgrade offers it. This runs after the dialog's own cleanup, which releases the
  // page, because a child's effects run before its parent's.
  useEffect(() => {
    if (!focusRescheduleNext || abandonOpen) return;
    rescheduleButton.current?.focus();
  }, [focusRescheduleNext, abandonOpen]);
  const abandonMutation = useMutation({
    mutationFn: () => performUpgradeAction(upgrade.id, 'ABANDONED'),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** Asks before abandoning, without a refusal left over from an earlier attempt. */
  const openAbandon = () => {
    abandonMutation.reset();
    setFocusRescheduleNext(false);
    setAbandonOpen(true);
  };

  /** Closes the confirmation, but not while the abandon is in flight, for the reason {@link closeDelete} gives. */
  const closeAbandon = () => {
    if (!abandonMutation.isPending) setAbandonOpen(false);
  };

  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  // The date the reschedule dialog opens on. What the user picks is read from the field when they submit.
  const [rescheduleOpensOn, setRescheduleOpensOn] = useState('');
  const [dateCheck, setDateCheck] = useState('');
  const newDateField = useRef<HTMLInputElement>(null);
  const rescheduleMutation = useMutation({
    mutationFn: (date: string) => rescheduleUpgrade(upgrade.id, date),
    onSuccess: () => refreshUpgradeViews(qc),
  });

  /** Opens on the planned start, or on the user's today when there is none. */
  const openReschedule = () => {
    setRescheduleOpensOn(upgrade.plannedStartDate ?? todayLocal());
    setDateCheck('');
    rescheduleMutation.reset();
    setRescheduleOpen(true);
  };

  const submitReschedule = (e: React.FormEvent) => {
    e.preventDefault();
    const newDate = newDateField.current?.value ?? '';
    // Before the empty check, which an unfinished date would otherwise meet as "no date picked".
    if (holdsUnreadableText(newDateField.current)) {
      setDateCheck(UNFINISHED_DATE);
      newDateField.current?.focus();
      return;
    }
    if (!newDate) {
      setDateCheck('Pick a start date.');
      newDateField.current?.focus();
      return;
    }
    if (!isFourDigitYearDate(newDate)) {
      setDateCheck('Pick a date with a four-digit year.');
      newDateField.current?.focus();
      return;
    }
    setDateCheck('');
    rescheduleMutation.mutate(newDate, { onSuccess: () => setRescheduleOpen(false) });
  };

  const rescheduleRefusal = rescheduleMutation.error
    ? toFormMessage(toApiError(rescheduleMutation.error), 'newDate')
    : undefined;

  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteMutation = useMutation({
    mutationFn: () => deleteUpgrade(upgrade.id),
    onSuccess: () => {
      // Dropped before the refresh, which would otherwise refetch the page's own read into a 404 while
      // the page is still on screen.
      qc.removeQueries({ queryKey: ['upgrade', upgrade.id], exact: true });
      return refreshUpgradeViews(qc);
    },
  });

  /** Asks before deleting, without a refusal left over from an earlier attempt. */
  const openDelete = () => {
    deleteMutation.reset();
    setDeleteOpen(true);
  };

  /**
   * Closes the confirmation, but not while the delete is in flight. Dismissed sooner, it could be opened
   * again and the delete sent twice, and the second would be answered 404 as though the first had failed.
   */
  const closeDelete = () => {
    if (!deleteMutation.isPending) setDeleteOpen(false);
  };

  /** Starts an edit from what the upgrade holds now, with nothing left over from an earlier attempt. */
  const openEdit = () => {
    setEditForm(editableFields(upgrade));
    setEditCheck({});
    editMutation.reset();
    setEditOpen(true);
  };

  const submitEdit = (e: React.FormEvent) => {
    e.preventDefault();
    // A refused check sends focus to the field it names, where its message is read with it.
    if (!editForm.title.trim()) {
      setEditCheck({ title: 'Title is required.' });
      titleField.current?.focus();
      return;
    }
    // An unfinished date reads as no date, and sent as one it would erase the stored target end.
    if (holdsUnreadableText(targetEndField.current)) {
      setEditCheck({ targetEnd: UNFINISHED_OPTIONAL_DATE });
      targetEndField.current?.focus();
      return;
    }
    if (editForm.targetEndDate && !isFourDigitYearDate(editForm.targetEndDate)) {
      setEditCheck({ targetEnd: 'Pick a date with a four-digit year.' });
      targetEndField.current?.focus();
      return;
    }
    setEditCheck({});
    editMutation.mutate(editForm, { onSuccess: () => setEditOpen(false) });
  };

  const editRefusal = editMutation.error ? toFormMessage(toApiError(editMutation.error), 'title') : undefined;

  return (
    <>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-line-subtle pt-4">
        <Button size="sm" variant="secondary" onClick={openEdit}>
          Edit Upgrade
        </Button>
        {RESCHEDULABLE.includes(upgrade.status) && (
          <Button ref={rescheduleButton} size="sm" variant="secondary" onClick={openReschedule}>
            Reschedule
          </Button>
        )}
        {ABANDONABLE.includes(upgrade.status) && (
          <Button size="sm" variant="secondary" onClick={openAbandon}>
            Abandon
          </Button>
        )}
        <Button size="sm" variant="danger" onClick={openDelete}>
          Delete
        </Button>
      </div>

      <Modal isOpen={editOpen} onClose={() => setEditOpen(false)} title="Edit Upgrade">
        <form onSubmit={submitEdit} noValidate className="space-y-4">
          {/* Without it the picker offers "No area" alone, as though the upgrade had none (NFR-30). The
              area the upgrade has is still sent back unchanged. */}
          {areasError && (
            <ErrorState inline title="Your health areas could not be loaded." error={toApiError(areasError)} />
          )}
          <UpgradeFormFields
            value={editForm}
            onChange={setEditForm}
            areas={areas}
            titleError={editCheck.title ?? editRefusal}
            targetEndError={editCheck.targetEnd}
            titleRef={titleField}
            targetEndRef={targetEndField}
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setEditOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={editMutation.isPending}>
              Save
            </Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={rescheduleOpen} onClose={() => setRescheduleOpen(false)} title="Reschedule Upgrade">
        <form onSubmit={submitReschedule} noValidate className="space-y-4">
          <p className="text-fg-subtle">{rescheduleQuestion(upgrade.status)}</p>
          {/* Uncontrolled, for the reason UpgradeFormFields gives its target end: the dialog mounts afresh
              on every open, so it starts from the date openReschedule set, and the submit reads what the
              user picked from the field. */}
          <Input
            ref={newDateField}
            label="New start date"
            type="date"
            defaultValue={rescheduleOpensOn}
            error={dateCheck || rescheduleRefusal}
            required
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setRescheduleOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={rescheduleMutation.isPending}>
              Reschedule
            </Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={abandonOpen} onClose={closeAbandon} title="Abandon Upgrade">
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
          <Button variant="secondary" onClick={closeAbandon} disabled={abandonMutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            loading={abandonMutation.isPending}
            onClick={() =>
              abandonMutation.mutate(undefined, {
                onSuccess: () => {
                  setAbandonOpen(false);
                  setFocusRescheduleNext(true);
                },
              })
            }
          >
            Abandon
          </Button>
        </div>
      </Modal>

      <Modal isOpen={deleteOpen} onClose={closeDelete} title="Delete Upgrade">
        <p className="text-fg-subtle mb-4">
          Delete <span className="font-medium text-fg break-words">{upgrade.title}</span>? Its tracking, progress,
          reminders and reflections are deleted with it, and this cannot be undone. Notifications about it stay.
        </p>
        {deleteMutation.error && (
          <div className="mb-4">
            <ErrorState inline title="The upgrade was not deleted." error={toApiError(deleteMutation.error)} />
          </div>
        )}
        <div className="flex gap-2 justify-end">
          <Button variant="secondary" onClick={closeDelete} disabled={deleteMutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            loading={deleteMutation.isPending}
            onClick={() =>
              deleteMutation.mutate(undefined, { onSuccess: () => navigate('/dashboard', { replace: true }) })
            }
          >
            Delete
          </Button>
        </div>
      </Modal>
    </>
  );
};

export default UpgradeActions;
