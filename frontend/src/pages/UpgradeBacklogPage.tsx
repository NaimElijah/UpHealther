import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getUpgrades, createUpgrade, performUpgradeAction } from '../api/upgrades';
import { getHealthAreas } from '../api/healthAreas';
import PageHeader from '../components/ui/PageHeader';
import Button from '../components/ui/Button';
import Modal from '../components/ui/Modal';
import Input from '../components/ui/Input';
import Select from '../components/ui/Select';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import EmptyState from '../components/ui/EmptyState';
import UpgradeCard from '../components/upgrade/UpgradeCard';
import type { CreateUpgradeRequest, UpgradeType, Difficulty, UpgradeStatus } from '../types';
import PageContainer from '../components/ui/PageContainer';

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

  const { data: upgrades = [], isLoading, error } = useQuery({ queryKey: ['upgrades', 'IDEA'], queryFn: () => getUpgrades('IDEA') });
  const { data: areas = [] } = useQuery({ queryKey: ['healthAreas'], queryFn: getHealthAreas });

  const createMutation = useMutation({
    mutationFn: createUpgrade,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['upgrades'] }); setIsOpen(false); setForm({ title: '', type: 'HABIT', difficulty: 'MEDIUM' }); },
  });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: UpgradeStatus }) => performUpgradeAction(id, status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['upgrades'] }),
  });

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError('');
    if (!form.title.trim()) { setFormError('Title is required.'); return; }
    await createMutation.mutateAsync(form);
  };

  const handleStatusChange = (id: string, status: UpgradeStatus) => {
    statusMutation.mutate({ id, status });
  };

  const areaOptions = [{ value: '', label: 'No area' }, ...areas.map((a) => ({ value: a.id, label: a.name }))];

  if (isLoading) return <div className="flex justify-center py-20"><LoadingSpinner size="lg" /></div>;
  if (error) return <p className="text-danger-fg text-center py-10">Failed to load the backlog.</p>;

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
            <UpgradeCard key={u.id} upgrade={u} onStatusChange={handleStatusChange} />
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
    </PageContainer>
  );
};

export default UpgradeBacklogPage;
