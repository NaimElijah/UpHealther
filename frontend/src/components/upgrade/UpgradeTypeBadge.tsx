import React from 'react';
import Badge from '../ui/Badge';
import type { UpgradeType } from '../../types';

interface Props {
  type: UpgradeType;
}

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

const UpgradeTypeBadge: React.FC<Props> = ({ type }) => {
  const { variant, label } = typeMap[type] ?? { variant: 'gray', label: type };
  return <Badge variant={variant}>{label}</Badge>;
};

export default UpgradeTypeBadge;
