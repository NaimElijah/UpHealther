/**
 * The check a `noValidate` form makes on its number and date fields before it sends anything.
 *
 * Such a field reports text it cannot read as a value of its type, `3e` in a number field or a date with
 * one part erased, as the value `''`, which is also what an empty field reports. Without `noValidate`
 * the browser refuses to submit in that state; with it, the form submits, and `''` reads as "none", which
 * an update, a full replacement, then stores over the value the field had (review finding 1 on #407).
 * Only `validity.badInput` tells the two apart, so the form reads it at submit time through the field's
 * ref. Read in `onChange` it goes stale, because typing `e` into an empty number field changes the text
 * but leaves the value `''`, and React calls no `onChange` for a value that did not change.
 *
 * A date field checked this way is uncontrolled (`defaultValue`). React rewrites a controlled field's
 * `value` attribute on every change, and once it has, Firefox clears an unfinished date as focus leaves
 * the field, for the submit button say, and `badInput` goes with it.
 */

/** Why an unfinished date in a field that may be left empty is refused. */
export const UNFINISHED_OPTIONAL_DATE = 'Finish the date, or clear it.';

/** Why an unfinished date in a required field is refused. */
export const UNFINISHED_DATE = 'Finish the date.';

/**
 * Whether the field holds text its browser cannot read as a value of its type.
 *
 * @param field the number or date input, as its ref holds it; `null` while it is not mounted
 * @returns true when the field's `validity.badInput` is set, false otherwise or when there is no field
 */
export const holdsUnreadableText = (field: HTMLInputElement | null): boolean => field?.validity.badInput === true;
