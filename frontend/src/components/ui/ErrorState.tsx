import React from 'react';
import type { ApiError } from '../../api/apiError';

/**
 * @param title  what failed, said plainly and without blame
 * @param error  the normalised failure, whose trace id is shown when there is one
 * @param inline compact, for an action that failed beside the control that took it — a dialog, a card,
 *               the bell's panel — rather than in place of a page that could not load
 */
interface ErrorStateProps {
  title: string;
  error?: ApiError;
  inline?: boolean;
}

/** The trace id, selectable on its own so a double-click grabs the id and nothing around it. */
const Reference: React.FC<{ traceId: string }> = ({ traceId }) => (
  <>
    Reference: <code className="select-all font-mono">{traceId}</code>
  </>
);

/**
 * What a page shows instead of the data it could not load, or beside an action that did not go through.
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
 *
 * The inline form takes the colours of the sign-out alert in the navbar, a pairing already checked for
 * contrast in both themes.
 */
const ErrorState: React.FC<ErrorStateProps> = ({ title, error, inline = false }) =>
  inline ? (
    <div className="rounded-lg border border-danger-line bg-danger-soft px-3 py-2 text-sm text-danger-fg" role="alert">
      <p className="font-medium">{title}</p>
      {error?.message && <p className="break-words">{error.message}</p>}
      {error?.traceId && (
        <p className="mt-1 text-xs">
          <Reference traceId={error.traceId} />
        </p>
      )}
    </div>
  ) : (
    <div className="flex flex-col items-center justify-center py-16 text-center" role="alert">
      <div className="text-5xl mb-4">⚠️</div>
      <h3 className="text-lg font-semibold text-danger-fg mb-1">{title}</h3>
      {error?.message && <p className="text-sm text-fg-subtle max-w-sm">{error.message}</p>}
      {error?.traceId && (
        <p className="mt-4 text-xs text-fg-faint">
          <Reference traceId={error.traceId} />
        </p>
      )}
    </div>
  );

export default ErrorState;
