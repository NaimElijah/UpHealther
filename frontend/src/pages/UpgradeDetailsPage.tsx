import React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getUpgradeById } from '../api/upgrades';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import Card from '../components/ui/Card';
import UpgradeStatusBadge from '../components/upgrade/UpgradeStatusBadge';
import UpgradeTypeBadge from '../components/upgrade/UpgradeTypeBadge';
import UpgradeActions from '../components/upgrade/UpgradeActions';
import UpgradeStreak from '../components/upgrade/UpgradeStreak';
import UpgradeTrackingConfig from '../components/upgrade/UpgradeTrackingConfig';
import UpgradeReminders from '../components/upgrade/UpgradeReminders';
import UpgradeProgress from '../components/upgrade/UpgradeProgress';
import UpgradeReflections from '../components/upgrade/UpgradeReflections';
import {
  progressQuery,
  reflectionsQuery,
  remindersQuery,
  streakQuery,
} from '../components/upgrade/upgradeDetailsQueries';
import Badge from '../components/ui/Badge';
import { difficultyBadgeVariant } from '../components/upgrade/upgradeMeta';
import PageContainer from '../components/ui/PageContainer';
import ErrorState from '../components/ui/ErrorState';
import { toApiError } from '../api/apiError';
import { parseLocalDate } from '../lib/localDate';

/**
 * Everything about one upgrade: its details, and below them its streak, tracking configuration,
 * reminders, progress history and reflections.
 *
 * The page holds every read, the header and the layout. It reads the sections' data beside the
 * upgrade, as it mounts, and hands each section below the header its result. Each section is a
 * component of `components/upgrade/` that owns its writes and its dialog, where it has them, so saving
 * a reflection does not refetch the progress history; what they share is the upgrade id from the route.
 */
const UpgradeDetails: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const {
    data: upgrade,
    isLoading,
    error,
  } = useQuery({ queryKey: ['upgrade', id], queryFn: () => getUpgradeById(id!), enabled: !!id });
  // Read here rather than in the sections, which render only once the upgrade has loaded: a read
  // started there would wait for the upgrade's. Each section is handed its read's result.
  const progressRead = useQuery(progressQuery(id));
  const reflectionsRead = useQuery(reflectionsQuery(id));
  const streakRead = useQuery(streakQuery(id));
  const remindersRead = useQuery(remindersQuery(id));

  if (isLoading)
    return (
      <div className="flex justify-center py-20">
        <LoadingSpinner size="lg" />
      </div>
    );
  // Only a first load that failed has nothing to show. A later read that fails leaves the last upgrade
  // read in the cache, so the page and its sections stay mounted, with any open dialog and its draft,
  // and the failure is shown above them.
  if (!upgrade)
    return <ErrorState title="Could not load this upgrade." error={error ? toApiError(error) : undefined} />;

  return (
    <PageContainer width="narrow" className="space-y-6">
      <button onClick={() => navigate(-1)} className="text-sm text-brand-fg hover:underline">
        ← Back
      </button>

      {error && (
        <ErrorState
          inline
          title="This upgrade could not be refreshed. What is shown may be out of date."
          error={toApiError(error)}
        />
      )}

      <Card>
        <div className="flex flex-wrap gap-2 mb-3">
          <UpgradeStatusBadge status={upgrade.status} />
          <UpgradeTypeBadge type={upgrade.type} />
          <Badge variant={difficultyBadgeVariant(upgrade.difficulty)}>{upgrade.difficulty}</Badge>
        </div>
        <h1 className="text-2xl font-bold text-fg break-words">{upgrade.title}</h1>
        {upgrade.description && <p className="text-fg-subtle mt-2 break-words">{upgrade.description}</p>}
        {upgrade.motivation && (
          <div className="mt-4 bg-brand-soft rounded-lg p-3">
            <p className="text-sm text-brand-fg break-words">
              <strong>Motivation:</strong> {upgrade.motivation}
            </p>
          </div>
        )}
        {upgrade.successCriteria && (
          <div className="mt-2 bg-success-soft rounded-lg p-3">
            <p className="text-sm text-success-fg break-words">
              <strong>Success Criteria:</strong> {upgrade.successCriteria}
            </p>
          </div>
        )}
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm text-fg-subtle">
          {upgrade.plannedStartDate && (
            <div>
              <span className="font-medium">Planned Start:</span>{' '}
              {parseLocalDate(upgrade.plannedStartDate).toLocaleDateString()}
            </div>
          )}
          {upgrade.actualStartDate && (
            <div>
              <span className="font-medium">Actual Start:</span>{' '}
              {parseLocalDate(upgrade.actualStartDate).toLocaleDateString()}
            </div>
          )}
          {upgrade.targetEndDate && (
            <div>
              <span className="font-medium">Target End:</span>{' '}
              {parseLocalDate(upgrade.targetEndDate).toLocaleDateString()}
            </div>
          )}
        </div>
        <UpgradeActions upgrade={upgrade} />
      </Card>

      <UpgradeStreak read={streakRead} />
      <UpgradeTrackingConfig upgradeId={id!} trackingConfig={upgrade.trackingConfig} />
      <UpgradeReminders upgradeId={id!} read={remindersRead} />
      <UpgradeProgress upgradeId={id!} read={progressRead} trackingConfig={upgrade.trackingConfig} />
      <UpgradeReflections upgradeId={id!} read={reflectionsRead} />
    </PageContainer>
  );
};

/**
 * The upgrade page as the router mounts it: one instance per upgrade.
 *
 * React Router keeps an element mounted when only `:id` changes, which is how the notification bell, a
 * toast and the notifications page move between upgrades. Every form, open dialog and in-flight save on
 * the page belongs to one upgrade, so keying on the id starts each upgrade from a fresh instance instead
 * of carrying any of them across (#116). An in-flight save then finishes against its own upgrade's
 * caches, because the instance that started it is the one whose callbacks run.
 */
const UpgradeDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  return <UpgradeDetails key={id} />;
};

export default UpgradeDetailsPage;
