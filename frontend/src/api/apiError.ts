import axios from 'axios';

/**
 * A failed API call, in the shape the interface actually needs.
 *
 * `traceId` is the point of this module. The backend goes out of its way to produce one — it is on
 * every log line, on the `X-Trace-Id` response header and on the body of every error it builds
 * (NFR-21) — precisely so that somebody reporting a failure can quote one string that finds the
 * request in the log. Discarding it, which is what every call site did before, threw away the only
 * thing that made a bug report actionable.
 */
export interface ApiError {
  /** HTTP status, or `undefined` when the request never got a response at all. */
  status?: number;
  /** The backend's own message where it gave one, otherwise a sentence the user can read. */
  message: string;
  /** Field name → message, populated only for a validation failure (a 400). */
  fieldErrors?: Record<string, string>;
  /** The id that finds this request in the log, when the response carried one. */
  traceId?: string;
}

/** Shown when the request never reached the API, or reached it and produced nothing to quote. */
const UNREACHABLE = 'Could not reach the server. Check your connection and try again.';
const UNEXPLAINED = 'Something went wrong.';

/**
 * The backend's error body (`GlobalExceptionHandler.ErrorResponse`).
 *
 * Every field is optional here even though the server always sends `status`, `message` and `path`:
 * this is parsing untrusted input, and a proxy returning its own HTML on a 502 is a real case.
 */
interface ErrorResponseBody {
  message?: unknown;
  fieldErrors?: unknown;
  traceId?: unknown;
}

/** Header the backend echoes the trace id on, set before the chain runs so it survives a rejection. */
const TRACE_ID_HEADER = 'x-trace-id';

/**
 * Normalises anything a failed call threw into an {@link ApiError}.
 *
 * One place decodes the wire contract, mirroring how `types/index.ts` mirrors the DTOs — so a change
 * to the error body is a change to this file rather than to each of the pages that renders one.
 *
 * The trace id is read from the body first and the header second. Both carry it and they agree, but
 * the two have different coverage: a request refused inside the security chain never reaches the
 * exception handler that writes the body, and comes back with the header alone.
 *
 * @param thrown whatever the call rejected with — an axios error, an `Error`, or anything at all
 * @returns a value that is always safe to render
 */
export function toApiError(thrown: unknown): ApiError {
  if (!axios.isAxiosError(thrown)) {
    return { message: thrown instanceof Error && thrown.message ? thrown.message : UNEXPLAINED };
  }

  const { response } = thrown;
  if (!response) {
    // No response at all: a network failure, a CORS rejection or a timeout. There is no trace id to
    // quote because nothing on the server ever saw the request.
    return { message: UNREACHABLE };
  }

  const body: ErrorResponseBody = (response.data ?? {}) as ErrorResponseBody;

  return {
    status: response.status,
    message: typeof body.message === 'string' && body.message ? body.message : UNEXPLAINED,
    fieldErrors: asFieldErrors(body.fieldErrors),
    traceId: asTraceId(body.traceId) ?? asTraceId(response.headers?.[TRACE_ID_HEADER]),
  };
}

/** Keeps only a string→string map, so a malformed body cannot put an object where a message goes. */
function asFieldErrors(value: unknown): Record<string, string> | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const entries = Object.entries(value).filter(([, message]) => typeof message === 'string');
  return entries.length > 0 ? (Object.fromEntries(entries) as Record<string, string>) : undefined;
}

/** Absent rather than empty, so a caller can test for it without also testing for `''`. */
function asTraceId(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}
