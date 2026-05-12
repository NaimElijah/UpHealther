import React from 'react';
import Badge from '../ui/Badge';
import type { UpgradeStatus } from '../../types';

interface Props {
  status: UpgradeStatus;
}

const statusMap: Record<UpgradeStatus, { variant: 'green' | 'yellow' | 'blue' | 'red' | 'gray' | 'purple'; label: string }> = {
  IDEA: { variant: 'gray', label: 'Idea' },
  PLANNED: { variant: 'blue', label: 'Planned' },
  ACTIVE: { variant: 'green', label: 'Active' },
  PAUSED: { variant: 'yellow', label: 'Paused' },
  COMPLETED: { variant: 'purple', label: 'Completed' },
  ABANDONED: { variant: 'red', label: 'Abandoned' },
};

const UpgradeStatusBadge: React.FC<Props> = ({ status }) => {
  const { variant, label } = statusMap[status] ?? { variant: 'gray', label: status };
  return <Badge variant={variant}>{label}</Badge>;
};

export default UpgradeStatusBadge;
