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
 * Needed despite the map above being total: `difficulty` arrives as unvalidated JSON from the API, so
 * its static type is a claim about the backend rather than a fact about the value.
 */
export const UNKNOWN_DIFFICULTY_VARIANT: BadgeVariant = 'gray';

/**
 * The badge variant for a difficulty, including one the backend does not define.
 *
 * The fallback lives here rather than at each `<Badge>` because it used to live at neither: the details
 * page had an inline ternary that fell through to red, so an unknown difficulty was shown to the user as
 * HARD. Two call sites applying `?? UNKNOWN_DIFFICULTY_VARIANT` themselves is the same shape of bug
 * waiting to happen — one of them eventually forgets. One function is one place to get it wrong, and one
 * place to test.
 *
 * @param difficulty the value from the API, which is typed as `Difficulty` but not validated as one
 */
export function difficultyBadgeVariant(difficulty: Difficulty | null | undefined): BadgeVariant {
  if (!difficulty) return UNKNOWN_DIFFICULTY_VARIANT;
  return difficultyVariant[difficulty] ?? UNKNOWN_DIFFICULTY_VARIANT;
}
