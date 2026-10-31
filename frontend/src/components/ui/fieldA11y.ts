/**
 * What `Input` and `Select` add to their control so its label, its error and its required state reach
 * assistive technology (WCAG 1.3.1, 3.3.1, 3.3.2, 4.1.2). A module of its own, shared by the two, because
 * `react-refresh/only-export-components` refuses a component file that exports a function.
 */

/** The ids a field's parts are tied together by, all derived from one `useId`. */
export interface FieldIds {
  control: string;
  error: string;
}

/**
 * The control's id and its error's id. A caller's `id` is kept, so a page that links to a field by id
 * still can. Otherwise the id is React's, unique per field: deriving it from the label, as before, gave
 * two fields with one label the same id, and the second label then named the first field.
 */
export const fieldIds = (generated: string, id: string | undefined): FieldIds => ({
  control: id ?? `${generated}-control`,
  error: `${generated}-error`,
});

/**
 * The attributes that tie the control to its error and state its required-ness: `aria-invalid` and the
 * error's id in `aria-describedby` while there is an error, beside any description the caller gave, and
 * `aria-required` beside the native `required`.
 */
export const fieldAria = (
  ids: FieldIds,
  { error, required, describedBy }: { error?: string; required?: boolean; describedBy?: string },
) => ({
  'aria-invalid': error ? true : undefined,
  'aria-describedby': [describedBy, error ? ids.error : undefined].filter(Boolean).join(' ') || undefined,
  'aria-required': required || undefined,
});
