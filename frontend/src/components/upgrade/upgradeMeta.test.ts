import { describe, it, expect } from 'vitest';
import { difficultyVariant, UNKNOWN_DIFFICULTY_VARIANT } from './upgradeMeta';
import type { Difficulty } from '../../types';

/**
 * The difficulty-to-colour map, which exists because two places disagreed: the upgrade card used a map
 * and the details page an inline ternary whose final branch fell through to red for anything unknown —
 * so an unrecognised value was shown to the user as HARD.
 *
 * The map is total over the union, so the type checker covers the known values. What it cannot cover is
 * the unknown one: `difficulty` arrives as unvalidated JSON, so its static type is a claim about the
 * backend rather than a fact about the value on screen.
 */
describe('difficultyVariant', () => {
  it('GivenEachDifficulty_WhenItsBadgeVariantIsRead_ThenTheColoursRunGreenThroughRed', () => {
    expect(difficultyVariant.EASY).toBe('green');
    expect(difficultyVariant.MEDIUM).toBe('yellow');
    expect(difficultyVariant.HARD).toBe('red');
  });

  it('GivenTheKnownDifficulties_WhenTheMapIsInspected_ThenItCoversEveryOneOfThem', () => {
    // A difficulty added to the union without a colour here would render a badge with no colour at
    // all, because Tailwind emits nothing for a class it does not recognise.
    const known: Difficulty[] = ['EASY', 'MEDIUM', 'HARD'];
    expect(Object.keys(difficultyVariant).sort()).toEqual([...known].sort());
  });

  it('GivenADifficultyTheBackendDoesNotDefine_WhenItIsRendered_ThenTheFallbackSaysNothingRatherThanHard', () => {
    const fromApi = 'IMPOSSIBLE' as Difficulty;

    expect(difficultyVariant[fromApi] ?? UNKNOWN_DIFFICULTY_VARIANT).toBe('gray');
  });
});
