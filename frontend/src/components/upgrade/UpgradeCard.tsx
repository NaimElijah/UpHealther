import React from 'react';
import { useNavigate } from 'react-router-dom';
import type { ActionTarget, HealthUpgrade } from '../../types';
import UpgradeStatusBadge from './UpgradeStatusBadge';
import UpgradeTypeBadge from './UpgradeTypeBadge';
import Badge from '../ui/Badge';
import Button from '../ui/Button';
import { difficultyBadgeVariant } from './upgradeMeta';
import ErrorState from '../ui/ErrorState';
import type { ApiError } from '../../api/apiError';

/**
 * @param upgrade        the upgrade to show
 * @param onStatusChange called with the target status when a transition button is pressed; the card
 *                       does not perform the transition itself, so the page decides how to refresh
 * @param onPlan         called when Plan is pressed on an idea. Separate from `onStatusChange` because
 *                       planning needs a start date, so the page has to ask for one first (#88); Plan
 *                       is offered only where this is given
 * @param showActions    hide the transition buttons where the card is read-only
 * @param refusal        why the last transition pressed on this card was refused, shown inside it with its
 *                       trace id (NFR-30). The page owns the mutation, so the page says which card it was
 * @param busy           a transition is in flight on the page, so the transition buttons are disabled: a
 *                       second press would be refused, and one on another card would hide the first's answer
 */
interface Props {
  upgrade: HealthUpgrade;
  onStatusChange?: (id: string, status: ActionTarget) => void;
  onPlan?: (upgrade: HealthUpgrade) => void;
  showActions?: boolean;
  refusal?: ApiError;
  busy?: boolean;
}

/**
 * Summary card for one upgrade, with the transitions that are legal from its current status.
 *
 * The buttons shown mirror the server's state machine — plan an idea, activate a planned one, pause or
 * complete a running one, resume a paused one. Offering only the legal moves is what keeps a user from
 * meeting a 422 they could not have predicted.
 */
const UpgradeCard: React.FC<Props> = ({ upgrade, onStatusChange, onPlan, showActions = true, refusal, busy = false }) => {
  const navigate = useNavigate();

  return (
    <div className="bg-surface rounded-xl border border-line p-4 shadow-sm hover:shadow-md transition-shadow">
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <h3
            className="font-semibold text-fg truncate cursor-pointer hover:text-brand-fg"
            onClick={() => navigate(`/upgrades/${upgrade.id}`)}
          >
            {upgrade.title}
          </h3>
          {upgrade.description && (
            <p className="text-sm text-fg-subtle mt-1 line-clamp-2">{upgrade.description}</p>
          )}
        </div>
      </div>
      <div className="flex flex-wrap gap-2 mt-3">
        <UpgradeStatusBadge status={upgrade.status} />
        <UpgradeTypeBadge type={upgrade.type} />
        <Badge variant={difficultyBadgeVariant(upgrade.difficulty)}>{upgrade.difficulty}</Badge>
      </div>
      {showActions && upgrade.status === 'IDEA' && onPlan && (
        <div className="flex flex-wrap gap-2 mt-3 pt-3 border-t border-line-subtle">
          <Button size="sm" variant="secondary" onClick={() => onPlan(upgrade)}>
            Plan
          </Button>
        </div>
      )}
      {showActions && upgrade.status !== 'IDEA' && onStatusChange && (
        <div className="flex flex-wrap gap-2 mt-3 pt-3 border-t border-line-subtle">
          {upgrade.status === 'PLANNED' && (
            <Button size="sm" disabled={busy} onClick={() => onStatusChange(upgrade.id, 'ACTIVE')}>
              Activate
            </Button>
          )}
          {upgrade.status === 'ACTIVE' && (
            <>
              <Button size="sm" variant="secondary" disabled={busy} onClick={() => onStatusChange(upgrade.id, 'PAUSED')}>
                Pause
              </Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => onStatusChange(upgrade.id, 'COMPLETED')}>
                Complete
              </Button>
            </>
          )}
          {upgrade.status === 'PAUSED' && (
            <Button size="sm" disabled={busy} onClick={() => onStatusChange(upgrade.id, 'ACTIVE')}>
              Resume
            </Button>
          )}
        </div>
      )}
      {refusal && (
        <div className="mt-3">
          <ErrorState inline title="That change did not go through." error={refusal} />
        </div>
      )}
    </div>
  );
};

export default UpgradeCard;
