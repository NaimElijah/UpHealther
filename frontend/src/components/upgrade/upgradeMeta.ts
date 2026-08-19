import type { BadgeVariant } from '../ui/Badge';
import type { Difficulty } from '../../types';

/**
 * Difficulty to badge colour: green through red, so HARD reads as the demanding one.
 *
 * Shared rather than duplicated: the upgrade card and the upgrade details page both render this badge,
 * and they disagreed — the card used this map and the details page an inline ternary whose final branch
 * fell through to red for any unknown value.
 *
 * Keyed on `Difficulty` rather than `string` so adding a difficulty to the union fails the build here
 * instead of rendering a colourless badge.
 */
export const difficultyVariant: Record<Difficulty, BadgeVariant> = {
  EASY: 'green',
  MEDIUM: 'yellow',
  HARD: 'red',
};

/**
 * The variant for an unrecognised difficulty. Grey says "no opinion" rather than mislabelling it HARD.
 *
 * Callers still need this despite the map above being total: `difficulty` arrives as unvalidated JSON
 * from the API, so its static type is a claim about the backend rather than a fact about the value.
 */
export const UNKNOWN_DIFFICULTY_VARIANT: BadgeVariant = 'gray';
