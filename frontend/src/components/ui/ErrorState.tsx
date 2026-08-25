import React from 'react';
import type { ApiError } from '../../api/apiError';

/**
 * @param title what failed, said plainly and without blame
 * @param error the normalised failure, whose trace id is shown when there is one
 */
interface ErrorStateProps {
  title: string;
  error?: ApiError;
}

/**
 * What a page shows instead of the data it could not load.
 *
 * The reason this exists rather than a hard-coded sentence per page is the **trace id**. The backend
 * puts one on every log line, on the `X-Trace-Id` header and on the error body specifically so that a
 * user reporting a failure can quote one string that finds their request. Seven pages used to render
 * `Failed to load X.` and throw the response away, which left a support conversation with nothing in
 * it — under any concurrent traffic, "it broke on the upgrades page" matches every failure that page
 * has ever produced.
 *
 * The id is shown quietly: a user who does not care never has to read it, and one who is reporting a
 * problem has something exact to paste. It is safe to display — a trace id identifies a request, never
 * a person, and is not an authorization input anywhere (ADR-007).
 */
const ErrorState: React.FC<ErrorStateProps> = ({ title, error }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center" role="alert">
    <div className="text-5xl mb-4">⚠️</div>
    <h3 className="text-lg font-semibold text-danger-fg mb-1">{title}</h3>
    {error?.message && <p className="text-sm text-fg-subtle max-w-sm">{error.message}</p>}
    {error?.traceId && (
      <p className="mt-4 text-xs text-fg-faint">
        Reference:{' '}
        {/* Selectable on its own so a double-click grabs the id and nothing around it. */}
        <code className="select-all font-mono">{error.traceId}</code>
      </p>
    )}
  </div>
);

export default ErrorState;
