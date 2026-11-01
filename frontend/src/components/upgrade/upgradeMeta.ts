import type { BadgeVariant } from '../ui/Badge';
import type { Difficulty } from '../../types';

/**
 * Difficulty to badge colour: green through red, so HARD reads as the demanding one.
 *
 * Shared rather than duplicated: the upgrade card and the upgrade details page both render this badge, so
 * they cannot disagree about a colour.
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
 * The fallback lives here rather than at each `<Badge>`, so no call site has to remember it: one function
 * is one place to get it wrong, and one place to test.
 *
 * @param difficulty the value from the API, which is typed as `Difficulty` but not validated as one
 */
export function difficultyBadgeVariant(difficulty: Difficulty | null | undefined): BadgeVariant {
  if (!difficulty) return UNKNOWN_DIFFICULTY_VARIANT;
  return difficultyVariant[difficulty] ?? UNKNOWN_DIFFICULTY_VARIANT;
}

/**
 * What a field the upgrade leaves empty reads as where its own label stands beside it, as on the
 * tracking card's rows. The difficulty badge has none, and reads {@link DIFFICULTY_NOT_SET_LABEL}.
 */
export const NOT_SET_LABEL = 'Not set';

/**
 * A value's label from a label map: {@link NOT_SET_LABEL} for none, and the raw value for one this
 * build does not know, as the status badge falls back, so a new value degrades to its name rather than
 * to nothing.
 *
 * @param labels what each known value is called
 * @param value  the value from the API, typed but not validated as one of `labels`' keys
 */
export function labelFor<T extends string>(labels: Record<T, string>, value: T | null | undefined): string {
  if (value == null) return NOT_SET_LABEL;
  return labels[value] ?? value;
}

/** What the form and the card call each difficulty. Keyed by the union, so a new one fails the build here. */
export const DIFFICULTY_LABELS: Record<Difficulty, string> = {
  EASY: 'Easy',
  MEDIUM: 'Medium',
  HARD: 'Hard',
};

/**
 * What the difficulty badge reads when the upgrade has none. The badge stands among others with no
 * label beside it, so a bare {@link NOT_SET_LABEL} would not say what is unset, to a screen reader
 * least of all: "Active, Habit, Not set".
 */
export const DIFFICULTY_NOT_SET_LABEL = 'Difficulty not set';

/**
 * The difficulty badge's text, on the upgrade card and the details page: the difficulty's label,
 * {@link DIFFICULTY_NOT_SET_LABEL} for none, and the raw value for one this build does not know.
 *
 * @param difficulty the value from the API, typed but not validated as a `Difficulty`
 */
export function difficultyLabel(difficulty: Difficulty | null | undefined): string {
  return difficulty == null ? DIFFICULTY_NOT_SET_LABEL : labelFor(DIFFICULTY_LABELS, difficulty);
}
