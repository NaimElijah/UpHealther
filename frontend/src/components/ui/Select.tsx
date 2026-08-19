import React from 'react';

/** One choice: the value submitted, and the text shown for it. */
interface SelectOption {
  value: string;
  label: string;
}

/**
 * Extends the native select attributes, so `value`, `onChange` and `required` pass through.
 *
 * @param label   optional caption, also used to derive an id when none is given
 * @param options the choices, rendered in the order given
 * @param error   message shown below the field, which also switches it to its error colours
 */
interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  options: SelectOption[];
  error?: string;
}

/** Labelled dropdown with inline error display, matching {@link Input}. */
const Select: React.FC<SelectProps> = ({ label, options, error, id, className = '', ...rest }) => {
  const selectId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={selectId} className="text-sm font-medium text-fg-muted">
          {label}
        </label>
      )}
      <select
        id={selectId}
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
      {error && <p className="text-xs text-danger-fg">{error}</p>}
    </div>
  );
};

export default Select;
