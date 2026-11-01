import { toApiError, toFormMessage } from '../../api/apiError';

/**
 * Parses a decimal field, treating unparseable input as absent.
 *
 * A cleared number input yields an empty string, and `parseFloat('')` is NaN. Kept in the form's state,
 * a NaN would come back as the controlled input's `value`, since `value ?? ''` lets it through, and
 * React refuses NaN there with a warning. Sent, it would serialise to `null`, which the API reads as no
 * value just as it reads an absent field.
 *
 * `parseFloat` takes a numeric prefix, so `numOrUndef('3e')` is 3. A number input reports text it cannot
 * read as `''` instead, which `unreadableInput.ts` tells from an empty field.
 */
export const numOrUndef = (v: string): number | undefined => {
  const n = parseFloat(v);
  return Number.isNaN(n) ? undefined : n;
};

/** Integer counterpart of {@link numOrUndef}, for the rating fields. */
export const intOrUndef = (v: string): number | undefined => {
  const n = parseInt(v, 10);
  return Number.isNaN(n) ? undefined : n;
};

/**
 * Why a form's last save was refused, for its main field (NFR-30); nothing while there is no refusal.
 *
 * Read from the mutation itself rather than copied into state: a new save clears it, and so does an
 * opener's `reset()`, which also detaches a save still in flight so its refusal cannot reach the next
 * dialog (#119).
 *
 * @param error   the mutation's `error`
 * @param primary the form's main field, whose own message is shown alone when it is the one refused
 */
export const refusalMessage = (error: Error | null, primary: string): string | undefined =>
  error ? toFormMessage(toApiError(error), primary) : undefined;
