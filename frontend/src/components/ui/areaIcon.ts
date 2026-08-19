/** Drawn for a health area with no icon, or one this application cannot render as a glyph. */
export const DEFAULT_AREA_ICON = '🎯';

/**
 * An icon *key* starts with an ASCII word character; a glyph does not.
 *
 * The seeded areas were written against Material Symbols ligature names — `water_drop`,
 * `fitness_center`, `self_improvement` — but no icon font is loaded anywhere, so the page drew those
 * names as their own literal text. The form has always asked for an emoji. This is where the two
 * readings are reconciled, on the render side, without trusting what is stored.
 */
const LOOKS_LIKE_AN_ICON_KEY = /^[\w-]/;

/**
 * The glyph to draw for a health area, given whatever the API returned.
 *
 * @param icon the stored icon, which is free-form text and validated nowhere
 * @returns    the stored value when it looks like a glyph, otherwise {@link DEFAULT_AREA_ICON}
 */
export const areaIconGlyph = (icon: string | null | undefined): string => {
  const trimmed = icon?.trim() ?? '';
  return trimmed === '' || LOOKS_LIKE_AN_ICON_KEY.test(trimmed) ? DEFAULT_AREA_ICON : trimmed;
};
