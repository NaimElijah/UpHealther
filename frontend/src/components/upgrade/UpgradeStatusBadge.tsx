import React from 'react';
import Badge from '../ui/Badge';
import type { UpgradeStatus } from '../../types';

/** @param status the upgrade's lifecycle status */
interface Props {
  status: UpgradeStatus;
}

/** Status to colour and label. Green for running, red for abandoned, neutral for an untouched idea. */
const statusMap: Record<UpgradeStatus, { variant: 'green' | 'yellow' | 'blue' | 'red' | 'gray' | 'purple'; label: string }> = {
  IDEA: { variant: 'gray', label: 'Idea' },
  PLANNED: { variant: 'blue', label: 'Planned' },
  ACTIVE: { variant: 'green', label: 'Active' },
  PAUSED: { variant: 'yellow', label: 'Paused' },
  COMPLETED: { variant: 'purple', label: 'Completed' },
  ABANDONED: { variant: 'red', label: 'Abandoned' },
};

/**
 * Coloured badge naming an upgrade's status.
 *
 * Falls back to the raw value for a status this build does not know, so a backend that gains one
 * degrades to showing its name rather than rendering an empty badge.
 */
const UpgradeStatusBadge: React.FC<Props> = ({ status }) => {
  const { variant, label } = statusMap[status] ?? { variant: 'gray', label: status };
  return <Badge variant={variant}>{label}</Badge>;
};

export default UpgradeStatusBadge;
