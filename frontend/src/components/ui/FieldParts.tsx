import React from 'react';

/**
 * @param htmlFor  the control's id
 * @param label    the caption, which is the control's accessible name
 * @param required draws the required marker beside the caption
 */
interface FieldLabelProps {
  htmlFor: string;
  label: string;
  required?: boolean;
}

/**
 * A field's caption, with a visible marker when the field is required (WCAG 3.3.2).
 *
 * The marker sits outside the `<label>` and is hidden from assistive technology: the control's own
 * `required` already says so to a screen reader, and inside the label the asterisk would become part of
 * the field's name.
 */
export const FieldLabel: React.FC<FieldLabelProps> = ({ htmlFor, label, required = false }) => (
  <span className="flex gap-1 text-sm font-medium text-fg-muted">
    <label htmlFor={htmlFor}>{label}</label>
    {required && (
      <span aria-hidden="true" className="text-danger-fg">
        *
      </span>
    )}
  </span>
);

/**
 * Says what the asterisk beside a caption means, at the top of a form with a required field (WCAG 3.3.2).
 *
 * Hidden from assistive technology, like the asterisk itself: a screen reader already hears "required"
 * on each such field, and "star, Required" would only be noise before it.
 */
export const RequiredFieldsNote: React.FC = () => (
  <p aria-hidden="true" className="text-xs text-fg-subtle">
    <span className="text-danger-fg">*</span> Required
  </p>
);

/**
 * @param id      the id the control's `aria-describedby` names
 * @param message what is wrong, or nothing when the field is fine
 */
interface FieldErrorProps {
  id: string;
  message?: string;
}

/**
 * A field's error, below it. Named by the control's `aria-describedby`, so it is read with the field,
 * and an alert, so it is announced when it appears (WCAG 3.3.1, 4.1.3).
 */
export const FieldError: React.FC<FieldErrorProps> = ({ id, message }) =>
  message ? (
    <p id={id} role="alert" className="text-xs text-danger-fg">
      {message}
    </p>
  ) : null;
