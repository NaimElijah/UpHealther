import axios from 'axios';

/**
 * A failed API call, in the shape the interface actually needs.
 *
 * `traceId` is the point of this module. The backend goes out of its way to produce one — it is on
 * every log line, on the `X-Trace-Id` response header and on the body of every error it builds
 * (NFR-21) — precisely so that somebody reporting a failure can quote one string that finds the
 * request in the log. Discarding it throws away the only thing that makes a bug report actionable.
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

/**
 * The sentence to put in front of a user for a failed call, preferring the specific over the general.
 *
 * A validation failure carries its detail in `fieldErrors` and puts only "Validation failed" in
 * `message`, so rendering `message` alone would tell somebody that something is wrong and nothing about
 * what, as with a 400 for an over-long field or a short password. Field messages come first for that
 * reason, and the trace id is appended only when there is no field detail, because "reference 7f3a..."
 * helps a bug report and not a form.
 *
 * Field errors are listed sorted by field name, each as `Name: message.`, joined by spaces; when `primary` is
 * among them, its message alone is returned.
 *
 * @param error   a decoded failure from {@link toApiError}
 * @param primary the form's main field, whose message is shown alone when it is the one that failed
 * @returns a string that is always safe to render
 */
export function toFormMessage(error: ApiError, primary?: string): string {
  const fields = error.fieldErrors;
  if (fields) {
    if (primary && fields[primary]) return capitalise(fields[primary]);
    return Object.entries(fields)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([field, message]) => `${capitalise(field)}: ${message}.`)
      .join(' ');
  }
  return error.traceId ? `${error.message} (reference ${error.traceId})` : error.message;
}

/** Bean-validation messages arrive lowercase ("size must be between..."), mid-sentence in shape. */
function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Shown when the request got no response at all, so there is no message and no trace id to quote. */
const UNREACHABLE = 'Could not reach the server. Check your connection and try again.';
/** Shown for a thrown value that is not an `Error` with a message, and for a response whose body has no message. */
const UNEXPLAINED = 'Something went wrong.';

/**
 * The backend's error body: `GlobalExceptionHandler.ErrorResponse`, or the container's error page, which
 * has Boot's shape (`timestamp`, `status`, `error`, `path`, `traceId`) and no `message` (ADR-024).
 *
 * Every field is optional here. The error page sends no `message`, and this is parsing untrusted input
 * anyway: a proxy returning its own HTML on a 502 is a real case.
 */
interface ErrorResponseBody {
  message?: unknown;
  fieldErrors?: unknown;
  traceId?: unknown;
}

/**
 * Header the backend echoes the trace id on, set before the chain runs so it survives a rejection. Written lower
 * case: it is read by bracket access, which `apiError.test.ts` pins against a real `AxiosHeaders`.
 */
const TRACE_ID_HEADER = 'x-trace-id';

/**
 * Normalises anything a failed call threw into an {@link ApiError}.
 *
 * One place decodes the error body, which is not one of the generated wire types: the exception
 * handler writes it, not a controller. So a change to it is a change to this file rather than to each
 * of the pages that renders one.
 *
 * The trace id is read from the body first and the header second. Every error body the backend builds
 * carries it, and agrees with the header, so the header is the fallback for a response whose body is
 * not the API's JSON at all.
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
    // quote, because the response that would carry one never reached this tab; the server may still
    // have seen the request.
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
