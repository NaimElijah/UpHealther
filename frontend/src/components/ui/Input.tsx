import React from 'react';

/**
 * Extends the native input attributes, so `type`, `value`, `onChange` and validation attributes pass
 * through unchanged.
 *
 * @param label optional caption, also used to derive an id when none is given
 * @param error message shown below the field, which also switches it to its error colours
 */
interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

/**
 * Labelled text input with inline error display.
 *
 * When no `id` is supplied one is derived from the label, which is what keeps the `htmlFor` association
 * intact — without it, clicking the label would not focus the field and screen readers would not
 * announce it.
 */
const Input: React.FC<InputProps> = ({ label, error, id, className = '', ...rest }) => {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={inputId} className="text-sm font-medium text-fg-muted">
          {label}
        </label>
      )}
      <input
        id={inputId}
        className={`rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 ${
          error ? 'border-danger focus:ring-danger' : 'border-line-strong'
        } ${className}`}
        {...rest}
      />
      {error && <p className="text-xs text-danger-fg">{error}</p>}
    </div>
  );
};

export default Input;
