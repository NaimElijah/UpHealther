import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades, createUpgrade, planUpgrade } from '../api/upgrades';
import { getHealthAreas } from '../api/healthAreas';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import Modal from '../components/ui/Modal';
import Input from '../components/ui/Input';
import Select from '../components/ui/Select';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import type { CreateUpgradeRequest, HealthUpgrade, UpgradeType, Difficulty } from '../types';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError, toFormMessage } from '../api/apiError';
import { todayLocal } from '../lib/localDate';

/**
 * The upgrade types offered when creating one: the eight kinds the product defines.
 *
 * Deliberately not every value of `UpgradeType` — the legacy `PROTOCOL` still renders on rows that
 * carry it, but is not something to create more of.
 */
const typeOptions: { value: UpgradeType; label: string }[] = [
  { value: 'HABIT', label: 'Habit' },
  { value: 'ONE_TIME_ACTION', label: 'One-Time Action' },
  { value: 'PRODUCT_REPLACEMENT', label: 'Product Replacement' },
  { value: 'ROUTINE', label: 'Routine' },
  { value: 'GOAL', label: 'Goal' },
  { value: 'EXPERIMENT', label: 'Experiment' },
  { value: 'LEARNING_TASK', label: 'Learning Task' },
  { value: 'MEDICAL_PREVENTIVE', label: 'Medical / Preventive' },
];

/** Difficulty choices. HARD is capped at three concurrently active, and the API enforces that. */
const difficultyOptions: { value: Difficulty; label: string }[] = [
  { value: 'EASY', label: 'Easy' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HARD', label: 'Hard' },
];

/**
 * A start date the API can read. A date field accepts years past 9999 and gives them unsigned, while
 * the API's `LocalDate` reads such a year only with a sign (`+20260-01-01`), so it would refuse the body
 * with no field to name.
 */
const FOUR_DIGIT_YEAR_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The idea backlog: every upgrade still in `IDEA`, and the form that creates new ones.
 *
 * This is where upgrades enter the system — creation is not offered on the planned or active pages,
 * because a new upgrade always starts as an idea whatever the client asks for. Planning one from here
 * moves it off this list.
 */
const UpgradeBacklogPage: React.FC = () => {
  const qc = useQueryClient();
  const [isOpen, setIsOpen] = useState(false);
  const [form, setForm] = useState<CreateUpgradeRequest>({ title: '', type: 'HABIT', difficulty: 'MEDIUM' });
  const [formError, setFormError] = useState('');
  const [planning, setPlanning] = useState<HealthUpgrade | null>(null);
  const [planDate, setPlanDate] = useState('');
  const [planError, setPlanError] = useState('');

  const { data: upgrades = [], isLoading, error } = useQuery({ queryKey: ['upgrades', 'IDEA'], queryFn: () => getUpgrades('IDEA') });
  const { data: areas = [] } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const createMutation = useMutation({
    mutationFn: createUpgrade,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['upgrades'] }); setIsOpen(false); setForm({ title: '', type: 'HABIT', difficulty: 'MEDIUM' }); },
  });

  const planMutation = useMutation({
    mutationFn: ({ id, date }: { id: string; date: string }) => planUpgrade(id, date),
    // Awaited, so the dialog stays pending until the backlog no longer lists the idea; closed sooner it
    // would uncover a Plan button whose second press earns a 422.
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['upgrades'] }); setPlanning(null); },
  });

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!form.title.trim()) { setFormError('Title is required.'); return; }
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
    if (!planDate) { setPlanError('Pick a start date.'); return; }
    if (!FOUR_DIGIT_YEAR_DATE.test(planDate)) { setPlanError('Pick a date with a four-digit year.'); return; }
    try {
      await planMutation.mutateAsync({ id: planning.id, date: planDate });
    } catch (thrown) {
      setPlanError(toFormMessage(toApiError(thrown), 'plannedStartDate'));
    }
  };

  const areaOptions = [{ value: '', label: 'No area' }, ...areas.map((a) => ({ value: a.id, label: a.name }))];

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <ErrorState title="Could not load your backlog." error={toApiError(error)} />;

  return (
    <PageContainer>
      <PageHeader
        title="Idea Backlog"
        subtitle="Health upgrade ideas waiting to be planned"
        action={<Button onClick={() => setIsOpen(true)}>+ New Idea</Button>}
      />
      {upgrades.length === 0 ? (
        <EmptyState icon="💡" title="No ideas yet" description="Capture your health upgrade ideas here before planning them." action={<Button onClick={() => setIsOpen(true)}>Add First Idea</Button>} />
      ) : (
        <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {upgrades.map((u) => (
            <UpgradeCard key={u.id} upgrade={u} onPlan={openPlan} />
          ))}
        </div>
      )}

      <Modal isOpen={isOpen} onClose={() => setIsOpen(false)} title="New Upgrade Idea">
        <form onSubmit={handleCreate} className="space-y-4">
          <Input label="Title" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="e.g. Drink 2L water daily" error={formError} />
          <Input label="Description" value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Optional details" />
          <Select label="Type" value={form.type} options={typeOptions} onChange={(e) => setForm({ ...form, type: e.target.value as UpgradeType })} />
          <Select label="Difficulty" value={form.difficulty} options={difficultyOptions} onChange={(e) => setForm({ ...form, difficulty: e.target.value as Difficulty })} />
          <Select label="Health Area" value={form.areaId ?? ''} options={areaOptions} onChange={(e) => setForm({ ...form, areaId: e.target.value || undefined })} />
          <Input label="Motivation" value={form.motivation ?? ''} onChange={(e) => setForm({ ...form, motivation: e.target.value })} placeholder="Why do you want this?" />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={() => setIsOpen(false)}>Cancel</Button>
            <Button type="submit" loading={createMutation.isPending}>Add Idea</Button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={!!planning} onClose={closePlan} title="Plan Upgrade">
        <form onSubmit={handlePlan} className="space-y-4">
          <p className="text-fg-subtle">When do you want to start <span className="font-medium text-fg">{planning?.title}</span>?</p>
          <Input label="Start date" type="date" value={planDate} onChange={(e) => setPlanDate(e.target.value)} error={planError} />
          <div className="flex gap-2 justify-end">
            <Button variant="secondary" type="button" onClick={closePlan} disabled={planMutation.isPending}>Cancel</Button>
            <Button type="submit" loading={planMutation.isPending}>Plan</Button>
          </div>
        </form>
      </Modal>
    </PageContainer>
  );
};

export default UpgradeBacklogPage;
