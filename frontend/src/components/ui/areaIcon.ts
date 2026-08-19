/** Drawn for a health area with no icon, or one this application cannot render as a glyph. */
export const DEFAULT_AREA_ICON = '🎯';

/**
 * An icon *key* is an ASCII identifier and nothing else: `water_drop`, `fitness_center`, `eco`.
 *
 * Anchored at both ends deliberately. A test on the first character alone would call `1️⃣` a key — its
 * first code point is an ASCII digit — and silently swap out a glyph the user chose.
 *
 * Note what this does *not* claim: it rejects what is recognisably a key, not everything that is not
 * an emoji. `!` and `™` are returned as they were stored. Deciding emoji-ness properly needs grapheme
 * segmentation, which costs a `lib` change and mangles skin tones and variation selectors where it is
 * unavailable — and buys nothing here, because the icon is drawn in a fixed clipping box that no value
 * can widen. See ADR-005.
 */
const ICON_KEY = /^[a-z][a-z0-9_-]*$/i;

/**
 * Whether a stored icon can be drawn as it stands.
 *
 * @param icon the stored icon, which is free-form text and validated nowhere
 */
export const isIconGlyph = (icon: string | null | undefined): boolean => {
  const trimmed = icon?.trim() ?? '';
  return trimmed !== '' && !ICON_KEY.test(trimmed);
};

/**
 * The glyph to draw for a health area, given whatever the API returned.
 *
 * @param icon the stored icon
 * @returns    the stored value, trimmed, when it can be drawn; otherwise {@link DEFAULT_AREA_ICON}
 */
export const areaIconGlyph = (icon: string | null | undefined): string => {
  const trimmed = icon?.trim() ?? '';
  return isIconGlyph(trimmed) ? trimmed : DEFAULT_AREA_ICON;
};
