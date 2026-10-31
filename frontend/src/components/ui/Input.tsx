import React, { useId } from 'react';
import { FieldError, FieldLabel } from './FieldParts';
import { fieldAria, fieldIds } from './fieldA11y';

/**
 * Extends the native input attributes, so `type`, `value`, `onChange`, `required` and the validation
 * attributes pass through unchanged.
 *
 * @param label optional caption, which names the field
 * @param error message shown below the field, which also marks it invalid and switches it to its error
 *              colours
 */
interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

/**
 * Labelled text input with inline error display.
 *
 * The label names the field through `htmlFor`, so clicking it focuses the field and a screen reader
 * announces it. An error is tied to the field by `aria-describedby` and `aria-invalid` and announced as
 * it appears, and a required field carries `aria-required` and a visible marker (WCAG 3.3.1, 3.3.2,
 * 4.1.2). The ids come from `useId` unless the caller gives one.
 *
 * Forwards its ref to the `<input>`, so a caller can move focus to the field.
 */
const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ label, error, id, required, className = '', 'aria-describedby': describedBy, ...rest }, ref) => {
    const ids = fieldIds(useId(), id);
    return (
      <div className="flex flex-col gap-1">
        {label && <FieldLabel htmlFor={ids.control} label={label} required={required} />}
        <input
          ref={ref}
          id={ids.control}
          required={required}
          {...fieldAria(ids, { error, required, describedBy })}
          className={`rounded-lg border bg-surface px-3 py-2 text-sm focus:outline-none focus:ring-2 ${
            error ? 'border-danger focus:ring-danger' : 'border-line-strong'
          } ${className}`}
          {...rest}
        />
        <FieldError id={ids.error} message={error} />
      </div>
    );
  },
);
Input.displayName = 'Input';

export default Input;
