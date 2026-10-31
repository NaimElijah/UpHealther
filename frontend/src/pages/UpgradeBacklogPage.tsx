import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades, createUpgrade, planUpgrade } from '../api/upgrades';
import { getHealthAreas } from '../api/healthAreas';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import Modal from '../components/ui/Modal';
import Input from '../components/ui/Input';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import UpgradeFormFields from '../components/upgrade/UpgradeFormFields';
import type { CreateUpgradeRequest, HealthUpgrade } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError, toFormMessage } from '../api/apiError';
import { isFourDigitYearDate, todayLocal } from '../lib/localDate';
import { refreshUpgradeViews } from '../lib/upgradeViews';
import { useFocusWhenReady } from '../hooks/useFocusWhenReady';
import { findPageHeading } from '../components/ui/pageHeadingFocus';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/** The create form's starting values, and what it returns to once an idea is added. */
const EMPTY_IDEA: CreateUpgradeRequest = { title: '', type: 'HABIT', difficulty: 'MEDIUM' };

/**
 * The idea backlog: every upgrade still in `IDEA`, and the form that creates new ones.
 *
 * This is where upgrades enter the system — creation is not offered on the planned or active pages,
 * because a new upgrade always starts as an idea whatever the client asks for. Planning one from here
 * moves it off this list.
 */
const UpgradeBacklogPage: React.FC = () => {
  useDocumentTitle('Idea Backlog');
  const qc = useQueryClient();
  const [isOpen, setIsOpen] = useState(false);
  const [form, setForm] = useState<CreateUpgradeRequest>(EMPTY_IDEA);
  const [formError, setFormError] = useState('');
  const [targetEndError, setTargetEndError] = useState('');
  const [planning, setPlanning] = useState<HealthUpgrade | null>(null);
  const [planDate, setPlanDate] = useState('');
  const [planError, setPlanError] = useState('');

  const {
    data: upgrades = [],
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrades', 'IDEA'], queryFn: () => getUpgrades('IDEA') });
  const { data: areas = [] } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const createMutation = useMutation({
    mutationFn: createUpgrade,
    onSuccess: () => {
      refreshUpgradeViews(qc);
      setIsOpen(false);
      setForm(EMPTY_IDEA);
    },
  });

  const focusWhenReady = useFocusWhenReady();
  const planMutation = useMutation({
    mutationFn: ({ id, date }: { id: string; date: string }) => planUpgrade(id, date),
    // Awaited, so the dialog stays pending until the backlog no longer lists the idea; closed sooner it
    // would uncover a Plan button whose second press earns a 422.
    // The planned idea's Plan button, which opened the dialog, has gone with it, so focus goes to the
    // page's heading rather than falling to <body>.
    onSuccess: async () => {
      await refreshUpgradeViews(qc);
      focusWhenReady(findPageHeading);
      setPlanning(null);
    },
  });

  /**
   * Opens the create dialog without a refusal left over from an attempt that was cancelled. The draft
   * itself is kept, so a dialog closed by mistake loses nothing.
   */
  const openCreate = () => {
    setFormError('');
    setTargetEndError('');
    setIsOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    setTargetEndError('');
    if (!form.title.trim()) {
      setFormError('Title is required.');
      return;
    }
    if (form.targetEndDate && !isFourDigitYearDate(form.targetEndDate)) {
      setTargetEndError('Pick a date with a four-digit year.');
      return;
    }
    try {
      await createMutation.mutateAsync(form);
    } catch (thrown) {
      // Without this the rejection was unhandled and the modal simply sat there: the API refuses an
      // over-long title with a 400 naming the field (BR-16), and none of it reached the form.
      setFormError(toFormMessage(toApiError(thrown), 'title'));
    }
  };

  /** Opens the plan dialog on today's date, without a message left over from an earlier attempt. */
  const openPlan = (upgrade: HealthUpgrade) => {
    setPlanDate(todayLocal());
    setPlanError('');
    setPlanning(upgrade);
  };

  /**
   * Closes the plan dialog, but not while its request is in flight. One mutation serves every idea, so
   * a dialog dismissed mid-flight would let another idea's open before this answer lands, and the
   * answer would close that one or put this refusal in it.
   */
  const closePlan = () => {
    if (!planMutation.isPending) setPlanning(null);
  };

  const handlePlan = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!planning) return;
    setPlanError('');
    if (!planDate) {
      setPlanError('Pick a start date.');
      return;
    }
    if (!isFourDigitYearDate(planDate)) {
      setPlanError('Pick a date with a four-digit year.');
      return;
    }
    try {
      await planMutation.mutateAsync({ id: planning.id, date: planDate });
    } catch (thrown) {
      setPlanError(toFormMessage(toApiError(thrown), 'plannedStartDate'));
    }
  };

  if (isLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  if (error) return <ErrorState title="Could not load your backlog." error={toApiError(error)} />;

  return (
    <PageContainer>
      <PageHeader
        title="Idea Backlog"
        subtitle="Health upgrade ideas waiting to be planned"
        action={<Button onClick={openCreate}>+ New Idea</Button>}
      />
      {upgrades.length === 0 ? (
        <EmptyState
          icon="💡"
          title="No ideas yet"
          description="Capture your health upgrade ideas here before planning them."
          action={<Button onClick={openCreate}>Add First Idea</Button>}
        />
      ) : (
        <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {upgrades.map((u) => (
            <UpgradeCard key={u.id} upgrade={u} onPlan={openPlan} />
          ))}
        </div>
      )}

      <Modal isOpen={isOpen} onClose={() => setIsOpen(false)} title="New Upgrade Idea">
        <form onSubmit={handleCreate} className="space-y-4">
          <UpgradeFormFields
            value={form}
            onChange={setForm}
            areas={areas}
            titleError={formError}
            targetEndError={targetEndError}
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setIsOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={createMutation.isPending}>
              Add Idea
            </Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={!!planning} onClose={closePlan} title="Plan Upgrade">
        <form onSubmit={handlePlan} className="space-y-4">
          <p className="text-fg-subtle">
            When do you want to start <span className="font-medium text-fg">{planning?.title}</span>?
          </p>
          <Input
            label="Start date"
            type="date"
            value={planDate}
            onChange={(e) => setPlanDate(e.target.value)}
            error={planError}
          />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={closePlan} disabled={planMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" loading={planMutation.isPending}>
              Plan
            </Button>
          </div>
        </form>
      </Modal>
    </PageContainer>
  );
};

export default UpgradeBacklogPage;
