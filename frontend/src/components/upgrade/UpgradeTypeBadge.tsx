import React from 'react';
import Badge from '../ui/Badge';
import type { UpgradeType } from '../../types';

/** @param type what kind of change the upgrade is */
interface Props {
  type: UpgradeType;
}

/**
 * Type to colour and label.
 *
 * Keyed by `UpgradeType`, so the compiler requires an entry for every value the backend can return —
 * including the legacy `PROTOCOL`, which is never offered in the create form but must still render on
 * a row that already carries it.
 */
const typeMap: Record<UpgradeType, { variant: 'green' | 'yellow' | 'blue' | 'red' | 'gray' | 'purple'; label: string }> = {
  HABIT: { variant: 'green', label: 'Habit' },
  ONE_TIME_ACTION: { variant: 'blue', label: 'One-Time' },
  PRODUCT_REPLACEMENT: { variant: 'purple', label: 'Product' },
  ROUTINE: { variant: 'yellow', label: 'Routine' },
  GOAL: { variant: 'blue', label: 'Goal' },
  EXPERIMENT: { variant: 'gray', label: 'Experiment' },
  LEARNING_TASK: { variant: 'purple', label: 'Learning' },
  MEDICAL_PREVENTIVE: { variant: 'red', label: 'Medical' },
  PROTOCOL: { variant: 'gray', label: 'Protocol' },
};

/** Coloured badge naming an upgrade's type, falling back to the raw value for an unknown one. */
const UpgradeTypeBadge: React.FC<Props> = ({ type }) => {
  const { variant, label } = typeMap[type] ?? { variant: 'gray', label: type };
  return <Badge variant={variant}>{label}</Badge>;
};

export default UpgradeTypeBadge;
