import React from 'react';
import type { SectionRead } from './upgradeDetailsQueries';
import type { StreakSummary } from '../../types';

/** @param read the page's read of the upgrade's streak */
interface Props {
  read: SectionRead<StreakSummary>;
}

/**
 * An upgrade's current streak and its longest, shown only while the current one is running. Until the
 * streak is read, and when it cannot be, nothing shows.
 *
 * Logging progress on the page refreshes it (`UpgradeProgress`).
 */
const UpgradeStreak: React.FC<Props> = ({ read }) => {
  const { data: streak = { current: 0, longest: 0 } } = read;

  return streak.current > 0 ? (
    <div className="bg-streak-soft border border-streak-line rounded-xl p-4 flex items-center gap-3">
      <span className="text-3xl" aria-hidden="true">
        🔥
      </span>
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
