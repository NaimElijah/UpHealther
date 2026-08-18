import React from 'react';
import { useNavigate } from 'react-router-dom';
import type { HealthUpgrade } from '../../types';
import UpgradeStatusBadge from './UpgradeStatusBadge';
import UpgradeTypeBadge from './UpgradeTypeBadge';
import Badge from '../ui/Badge';
import Button from '../ui/Button';

/**
 * @param upgrade        the upgrade to show
 * @param onStatusChange called with the target status when a transition button is pressed; the card
 *                       does not perform the transition itself, so the page decides how to refresh
 * @param showActions    hide the transition buttons where the card is read-only
 */
interface Props {
  upgrade: HealthUpgrade;
  onStatusChange?: (id: string, status: HealthUpgrade['status']) => void;
  showActions?: boolean;
}

/** Difficulty to badge colour: green through red, so HARD reads as the demanding one. */
const difficultyVariant: Record<string, 'green' | 'yellow' | 'red'> = {
  EASY: 'green',
  MEDIUM: 'yellow',
  HARD: 'red',
};

/**
 * Summary card for one upgrade, with the transitions that are legal from its current status.
 *
 * The buttons shown mirror the server's state machine — plan an idea, activate a planned one, pause or
 * complete a running one, resume a paused one. Offering only the legal moves is what keeps a user from
 * meeting a 422 they could not have predicted.
 */
const UpgradeCard: React.FC<Props> = ({ upgrade, onStatusChange, showActions = true }) => {
  const navigate = useNavigate();

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 shadow-sm hover:shadow-md transition-shadow">
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <h3
            className="font-semibold text-gray-900 truncate cursor-pointer hover:text-blue-600"
            onClick={() => navigate(`/upgrades/${upgrade.id}`)}
          >
            {upgrade.title}
          </h3>
          {upgrade.description && (
            <p className="text-sm text-gray-500 mt-1 line-clamp-2">{upgrade.description}</p>
          )}
        </div>
      </div>
      <div className="flex flex-wrap gap-2 mt-3">
        <UpgradeStatusBadge status={upgrade.status} />
        <UpgradeTypeBadge type={upgrade.type} />
        <Badge variant={difficultyVariant[upgrade.difficulty] ?? 'gray'}>{upgrade.difficulty}</Badge>
      </div>
      {showActions && onStatusChange && (
        <div className="flex flex-wrap gap-2 mt-3 pt-3 border-t border-gray-100">
          {upgrade.status === 'IDEA' && (
            <Button size="sm" variant="secondary" onClick={() => onStatusChange(upgrade.id, 'PLANNED')}>
              Plan
            </Button>
          )}
          {upgrade.status === 'PLANNED' && (
            <Button size="sm" onClick={() => onStatusChange(upgrade.id, 'ACTIVE')}>
              Activate
            </Button>
          )}
          {upgrade.status === 'ACTIVE' && (
            <>
              <Button size="sm" variant="secondary" onClick={() => onStatusChange(upgrade.id, 'PAUSED')}>
                Pause
              </Button>
              <Button size="sm" variant="ghost" onClick={() => onStatusChange(upgrade.id, 'COMPLETED')}>
                Complete
              </Button>
            </>
          )}
          {upgrade.status === 'PAUSED' && (
            <Button size="sm" onClick={() => onStatusChange(upgrade.id, 'ACTIVE')}>
              Resume
            </Button>
          )}
        </div>
      )}
    </div>
  );
};

export default UpgradeCard;
