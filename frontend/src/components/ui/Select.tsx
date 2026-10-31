import React, { useId } from 'react';
import { FieldError, FieldLabel } from './FieldParts';
import { fieldAria, fieldIds } from './fieldA11y';

/** One choice: the value submitted, and the text shown for it. */
interface SelectOption {
  value: string;
  label: string;
}

/**
 * Extends the native select attributes, so `value`, `onChange` and `required` pass through.
 *
 * @param label   optional caption, which names the field
 * @param options the choices, rendered in the order given
 * @param error   message shown below the field, which also marks it invalid and switches it to its error
 *                colours
 */
interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  options: SelectOption[];
  error?: string;
}

/**
 * Labelled dropdown with inline error display, tied to the field as `Input`'s is. Unlike `Input`, it
 * forwards no ref.
 */
const Select: React.FC<SelectProps> = ({
  label,
  options,
  error,
  id,
  required,
  className = '',
  'aria-describedby': describedBy,
  ...rest
}) => {
  const ids = fieldIds(useId(), id);
  return (
    <div className="flex flex-col gap-1">
      {label && <FieldLabel htmlFor={ids.control} label={label} required={required} />}
      <select
        id={ids.control}
        required={required}
        {...fieldAria(ids, { error, required, describedBy })}
        className={`rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 bg-surface ${
          error ? 'border-danger focus:ring-danger' : 'border-line-strong'
        } ${className}`}
        {...rest}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
      <FieldError id={ids.error} message={error} />
    </div>
  );
};

export default Select;
