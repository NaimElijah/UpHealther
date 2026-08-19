import { describe, expect, it } from 'vitest';
import { areaIconGlyph, DEFAULT_AREA_ICON } from './areaIcon';

describe('areaIconGlyph', () => {
  it('GivenAnEmoji_WhenTheGlyphIsResolved_ThenItIsReturnedUnchanged', () => {
    expect(areaIconGlyph('😴')).toBe('😴');
  });

  it('GivenAnEmojiWithSurroundingSpace_WhenTheGlyphIsResolved_ThenItIsTrimmed', () => {
    expect(areaIconGlyph('  💧 ')).toBe('💧');
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
});
