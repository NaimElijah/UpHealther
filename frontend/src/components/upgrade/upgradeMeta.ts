import type { BadgeVariant } from '../ui/Badge';

/**
 * Difficulty to badge colour: green through red, so HARD reads as the demanding one.
 *
 * Shared rather than duplicated: the upgrade card and the upgrade details page both render this badge,
 * and they disagreed — the card used this map and the details page an inline ternary whose final branch
 * fell through to red for any unknown value.
 */
export const difficultyVariant: Record<string, BadgeVariant> = {
  EASY: 'green',
  MEDIUM: 'yellow',
  HARD: 'red',
};

/** The variant for an unrecognised difficulty. Grey says "no opinion" rather than mislabelling it HARD. */
export const UNKNOWN_DIFFICULTY_VARIANT: BadgeVariant = 'gray';
