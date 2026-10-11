import React from 'react';
import Input from '../ui/Input';
import Select from '../ui/Select';
import type { CreateUpgradeRequest, Difficulty, HealthArea, UpgradeType } from '../../types';

/**
 * The upgrade types offered in the form: the eight kinds the product defines.
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
 * @param value      the upgrade as the form currently holds it
 * @param onChange   receives the whole upgrade with one field changed
 * @param areas      the caller's health areas, offered alongside "No area"
 * @param titleError why the last save was refused, shown under the title
 */
interface Props {
  value: CreateUpgradeRequest;
  onChange: (next: CreateUpgradeRequest) => void;
  areas: HealthArea[];
  titleError?: string;
}

/**
 * The fields that describe an upgrade, as one controlled group.
 *
 * Only the fields: the dialog, the submit button and what a save does belong to the form that hosts
 * them, so a new upgrade and an edited one can share what the user fills in without sharing what
 * happens next.
 */
const UpgradeFormFields: React.FC<Props> = ({ value, onChange, areas, titleError }) => {
  const set = (fields: Partial<CreateUpgradeRequest>) => onChange({ ...value, ...fields });
  const areaOptions = [{ value: '', label: 'No area' }, ...areas.map((a) => ({ value: a.id, label: a.name }))];

  return (
    <>
      <Input label="Title" value={value.title} onChange={(e) => set({ title: e.target.value })} placeholder="e.g. Drink 2L water daily" error={titleError} />
      <Input label="Description" value={value.description ?? ''} onChange={(e) => set({ description: e.target.value })} placeholder="Optional details" />
      <Select label="Type" value={value.type} options={typeOptions} onChange={(e) => set({ type: e.target.value as UpgradeType })} />
      <Select label="Difficulty" value={value.difficulty} options={difficultyOptions} onChange={(e) => set({ difficulty: e.target.value as Difficulty })} />
      <Select label="Health Area" value={value.areaId ?? ''} options={areaOptions} onChange={(e) => set({ areaId: e.target.value || undefined })} />
      <Input label="Motivation" value={value.motivation ?? ''} onChange={(e) => set({ motivation: e.target.value })} placeholder="Why do you want this?" />
    </>
  );
};

export default UpgradeFormFields;
