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
 * Covers this app's `UpgradeType` union, which is currently wider than the backend enum — see the note
 * on that type. The five values the API does not accept are unreachable in practice.
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
};

/** Coloured badge naming an upgrade's type, falling back to the raw value for an unknown one. */
const UpgradeTypeBadge: React.FC<Props> = ({ type }) => {
  const { variant, label } = typeMap[type] ?? { variant: 'gray', label: type };
  return <Badge variant={variant}>{label}</Badge>;
};

export default UpgradeTypeBadge;
