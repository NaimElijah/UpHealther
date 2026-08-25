import { describe, it, expect } from 'vitest';
import { difficultyBadgeVariant, difficultyVariant } from './upgradeMeta';
import type { Difficulty } from '../../types';

/**
 * The difficulty-to-colour mapping, which exists because two places disagreed: the upgrade card used a
 * map and the details page an inline ternary whose final branch fell through to red — so an
 * unrecognised value was shown to the user as HARD.
 *
 * Asserted through `difficultyBadgeVariant`, the function both call sites now use, rather than through
 * the map plus a `??` written in the test. The latter proves only that the fallback constant is grey,
 * and would stay green if a call site dropped the fallback and brought the bug back.
 */
describe('difficultyBadgeVariant', () => {
  it('GivenEachKnownDifficulty_WhenItsBadgeVariantIsRead_ThenTheColoursRunGreenThroughRed', () => {
    expect(difficultyBadgeVariant('EASY')).toBe('green');
    expect(difficultyBadgeVariant('MEDIUM')).toBe('yellow');
    expect(difficultyBadgeVariant('HARD')).toBe('red');
  });

  it('GivenTheKnownDifficulties_WhenTheMapIsInspected_ThenItCoversEveryOneOfThem', () => {
    // A difficulty added to the union without a colour here would render a badge with no colour at
    // all, because Tailwind emits nothing for a class it does not recognise.
    const known: Difficulty[] = ['EASY', 'MEDIUM', 'HARD'];
    expect(Object.keys(difficultyVariant).sort()).toEqual([...known].sort());
  });

  it('GivenADifficultyTheBackendDoesNotDefine_WhenItIsRendered_ThenTheFallbackSaysNothingRatherThanHard', () => {
    // `difficulty` is unvalidated JSON: its static type is a claim about the backend, not a fact.
    expect(difficultyBadgeVariant('IMPOSSIBLE' as Difficulty)).toBe('gray');
  });

  it('GivenAnUpgradeWithNoDifficultySet_WhenItIsRendered_ThenTheFallbackIsUsed', () => {
    // Difficulty is optional on an upgrade, so both absent forms reach this.
    expect(difficultyBadgeVariant(null)).toBe('gray');
    expect(difficultyBadgeVariant(undefined)).toBe('gray');
  });
});
