import React, { useState } from 'react';
import Input from '../ui/Input';
import Select from '../ui/Select';
import { DIFFICULTY_LABELS } from './upgradeMeta';
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
const difficultyOptions: { value: Difficulty; label: string }[] = (Object.keys(DIFFICULTY_LABELS) as Difficulty[]).map(
  (value) => ({ value, label: DIFFICULTY_LABELS[value] }),
);

/** The select's value for an upgrade that has no difficulty, which the API stores as null. */
const NO_DIFFICULTY = '';

/**
 * @param value          the upgrade as the form currently holds it
 * @param onChange       receives the whole upgrade with one field changed
 * @param areas          the caller's health areas, offered alongside "No area"
 * @param titleError     why the last save was refused, shown under the title
 * @param targetEndError why the target end cannot be sent, shown under it
 * @param titleRef       the title's input, so the hosting form can send focus to it when it refuses a save
 * @param targetEndRef   the target end's input, likewise
 */
interface Props {
  value: CreateUpgradeRequest;
  onChange: (next: CreateUpgradeRequest) => void;
  areas: HealthArea[];
  titleError?: string;
  targetEndError?: string;
  titleRef?: React.Ref<HTMLInputElement>;
  targetEndRef?: React.Ref<HTMLInputElement>;
}

/**
 * The fields that describe an upgrade, as one controlled group.
 *
 * Only the fields: the dialog, the submit button and what a save does belong to the form that hosts
 * them, so a new upgrade and an edited one can share what the user fills in without sharing what
 * happens next.
 *
 * The title is required, so a hosting form sets `noValidate` and checks it itself, as both forms do. It
 * then checks the target end's `validity.badInput` through `targetEndRef` too, because the browser no
 * longer refuses an unfinished date, which reads as none (`unreadableInput.ts`).
 */
const UpgradeFormFields: React.FC<Props> = ({
  value,
  onChange,
  areas,
  titleError,
  targetEndError,
  titleRef,
  targetEndRef,
}) => {
  const set = (fields: Partial<CreateUpgradeRequest>) => onChange({ ...value, ...fields });
  // The target end the fields mounted with, kept fixed: see the field below.
  const [openingTargetEnd] = useState(value.targetEndDate ?? '');
  // An upgrade of a type no longer offered keeps it as a choice. Missing from the list, the select would
  // show the first type instead, and read as a different kind of upgrade from the one being edited.
  const types = typeOptions.some((o) => o.value === value.type)
    ? typeOptions
    : [
        ...typeOptions,
        { value: value.type, label: `${value.type.charAt(0)}${value.type.slice(1).toLowerCase()} (no longer offered)` },
      ];
  // An upgrade with no difficulty, which only the API can create, is shown as having none until a
  // difficulty is chosen. Without the option, the select would show the first difficulty and read as an
  // upgrade the user had marked Easy. Like a retired type, it is not offered again once left, so a
  // change event only ever carries a difficulty.
  const difficulties = value.difficulty
    ? difficultyOptions
    : [{ value: NO_DIFFICULTY, label: 'Not set' }, ...difficultyOptions];
  const areaOptions = [{ value: '', label: 'No area' }, ...areas.map((a) => ({ value: a.id, label: a.name }))];

  return (
    <>
      <Input
        ref={titleRef}
        label="Title"
        value={value.title}
        onChange={(e) => set({ title: e.target.value })}
        placeholder="e.g. Drink 2L water daily"
        error={titleError}
        required
      />
      <Input
        label="Description"
        value={value.description ?? ''}
        onChange={(e) => set({ description: e.target.value })}
        placeholder="Optional details"
      />
      <Select
        label="Type"
        value={value.type}
        options={types}
        onChange={(e) => set({ type: e.target.value as UpgradeType })}
      />
      <Select
        label="Difficulty"
        value={value.difficulty ?? NO_DIFFICULTY}
        options={difficulties}
        onChange={(e) => set({ difficulty: e.target.value as Difficulty })}
      />
      <Select
        label="Health Area"
        value={value.areaId ?? ''}
        options={areaOptions}
        onChange={(e) => set({ areaId: e.target.value || undefined })}
      />
      <Input
        label="Motivation"
        value={value.motivation ?? ''}
        onChange={(e) => set({ motivation: e.target.value })}
        placeholder="Why do you want this?"
      />
      <Input
        label="Success Criteria"
        value={value.successCriteria ?? ''}
        onChange={(e) => set({ successCriteria: e.target.value })}
        placeholder="How will you know it worked?"
      />
      {/* Left out when cleared, not sent empty: an absent date is how the API is told there is none.
          Uncontrolled, from the value the fields mounted with, which a hosting dialog renders afresh on
          every open: React rewrites a controlled field's value attribute on every change, and Firefox then
          clears a date the user left unfinished as focus leaves it, `badInput` with it, so the hosting
          form's check could not see it (unreadableInput.ts). */}
      <Input
        ref={targetEndRef}
        label="Target End"
        type="date"
        defaultValue={openingTargetEnd}
        onChange={(e) => set({ targetEndDate: e.target.value || undefined })}
        error={targetEndError}
      />
    </>
  );
};

export default UpgradeFormFields;
