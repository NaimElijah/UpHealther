import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { READ_STARTED_BY_PAGE, streakQuery } from './upgradeDetailsQueries';

/** @param upgradeId the upgrade whose streak to show */
interface Props {
  upgradeId: string;
}

/**
 * An upgrade's current streak and its longest, shown only while the current one is running. Until the
 * streak is read, and when it cannot be, nothing shows.
 *
 * Logging progress on the page refreshes it (`UpgradeProgress`).
 */
const UpgradeStreak: React.FC<Props> = ({ upgradeId }) => {
  const { data: streak = { current: 0, longest: 0 } } = useQuery({
    ...streakQuery(upgradeId),
    ...READ_STARTED_BY_PAGE,
  });

  return streak.current > 0 ? (
    <div className="bg-streak-soft border border-streak-line rounded-xl p-4 flex items-center gap-3">
      <span className="text-3xl">🔥</span>
      <div>
        <p className="font-semibold text-streak-fg">{streak.current}-day streak!</p>
        <p className="text-sm text-streak-fg">
          Longest streak: {streak.longest} day{streak.longest === 1 ? '' : 's'}. Keep it up!
        </p>
      </div>
    </div>
  ) : null;
};

export default UpgradeStreak;
