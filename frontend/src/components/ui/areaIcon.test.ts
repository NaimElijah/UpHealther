import { describe, expect, it } from 'vitest';
import { areaIconGlyph, DEFAULT_AREA_ICON, isIconGlyph } from './areaIcon';

describe('areaIconGlyph', () => {
  it('GivenAnEmoji_WhenTheGlyphIsResolved_ThenItIsReturnedUnchanged', () => {
    expect(areaIconGlyph('😴')).toBe('😴');
  });

  it('GivenAnEmojiWithSurroundingSpace_WhenTheGlyphIsResolved_ThenItIsTrimmed', () => {
    expect(areaIconGlyph('  💧 ')).toBe('💧');
  });

  // The first code point of a keycap is an ASCII digit, so a first-character test would reject it.
  it('GivenAKeycapEmoji_WhenTheGlyphIsResolved_ThenItIsReturnedUnchanged', () => {
    expect(areaIconGlyph('1️⃣')).toBe('1️⃣');
  });

  it('GivenAnEmojiWithASkinToneOrVariationSelector_WhenTheGlyphIsResolved_ThenItSurvivesIntact', () => {
    expect(areaIconGlyph('👍🏽')).toBe('👍🏽');
    expect(areaIconGlyph('❤️')).toBe('❤️');
  });

  // The six seeded areas shipped with these, which is the whole reason the fallback exists.
  it('GivenAMaterialSymbolsKey_WhenTheGlyphIsResolved_ThenTheDefaultIsUsed', () => {
    expect(areaIconGlyph('water_drop')).toBe(DEFAULT_AREA_ICON);
    expect(areaIconGlyph('self_improvement')).toBe(DEFAULT_AREA_ICON);
    expect(areaIconGlyph('eco')).toBe(DEFAULT_AREA_ICON);
  });

  it('GivenAnEmptyIcon_WhenTheGlyphIsResolved_ThenTheDefaultIsUsed', () => {
    expect(areaIconGlyph('')).toBe(DEFAULT_AREA_ICON);
  });

  it('GivenWhitespaceOnly_WhenTheGlyphIsResolved_ThenTheDefaultIsUsed', () => {
    expect(areaIconGlyph('   ')).toBe(DEFAULT_AREA_ICON);
  });

  it('GivenNoIconAtAll_WhenTheGlyphIsResolved_ThenTheDefaultIsUsed', () => {
    expect(areaIconGlyph(undefined)).toBe(DEFAULT_AREA_ICON);
    expect(areaIconGlyph(null)).toBe(DEFAULT_AREA_ICON);
  });

  // Stated so the boundary is a decision on the record rather than an accident of the pattern.
  it('GivenPunctuationOrASymbol_WhenTheGlyphIsResolved_ThenItIsReturnedRatherThanRejected', () => {
    expect(areaIconGlyph('!')).toBe('!');
    expect(areaIconGlyph('™')).toBe('™');
  });
});

describe('isIconGlyph', () => {
  it('GivenAnIconKey_WhenAskedWhetherItIsAGlyph_ThenItIsNot', () => {
    expect(isIconGlyph('fitness_center')).toBe(false);
    expect(isIconGlyph('eco')).toBe(false);
  });

  it('GivenAnEmoji_WhenAskedWhetherItIsAGlyph_ThenItIs', () => {
    expect(isIconGlyph('🌙')).toBe(true);
  });

  it('GivenNothingStored_WhenAskedWhetherItIsAGlyph_ThenItIsNot', () => {
    expect(isIconGlyph('')).toBe(false);
    expect(isIconGlyph(null)).toBe(false);
    expect(isIconGlyph(undefined)).toBe(false);
  });
});
