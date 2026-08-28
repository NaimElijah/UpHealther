import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { toApiError, toFormMessage } from './apiError';

/**
 * The one place the backend's error contract is decoded.
 *
 * The trace id is what these tests are really about. The API sets `X-Trace-Id` on every response and
 * stamps `traceId` on every error body it builds (NFR-21), so that one string finds the request in the
 * log. Every call site used to discard it. The two sources are both covered because they have
 * different reach: a request refused inside the security chain never reaches the exception handler
 * that writes the body, so it comes back with the header alone.
 */
describe('toApiError', () => {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;

  // Real AxiosHeaders, not a plain object: apiError.ts reads the header by lower-cased bracket access,
  // and stubbing the container it reads from would make the fallback test assert against its own stub.
  // If axios ever stopped answering to that access, security-chain rejections would silently lose the
  // trace id and a plain-object test would stay green.
  function responded(status: number, data: unknown, headers: Record<string, string> = {}): AxiosError {
    const response = {
      data,
      status,
      statusText: '',
      headers: AxiosHeaders.from(headers),
      config,
    } as AxiosResponse;
    return new AxiosError('Request failed', String(status), config, {}, response);
  }

  it('GivenAnErrorBodyCarryingATraceId_WhenItIsNormalised_ThenTheIdSurvives', () => {
    const error = toApiError(responded(422, {
      status: 422,
      message: 'A completed upgrade cannot be paused',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    }));

    expect(error.status).toBe(422);
    expect(error.message).toBe('A completed upgrade cannot be paused');
    expect(error.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
  });

  it('GivenARejectionWithNoBody_WhenItIsNormalised_ThenTheHeaderSuppliesTheTraceId', () => {
    // A request refused inside the security chain never reaches GlobalExceptionHandler, so it carries
    // the header and not the body field. Without this fallback those failures would be unreportable.
    const error = toApiError(responded(403, '', { 'x-trace-id': '00f067aa0ba902b74bf92f3577b34da6' }));

    expect(error.traceId).toBe('00f067aa0ba902b74bf92f3577b34da6');
  });

  it('GivenAValidationFailure_WhenItIsNormalised_ThenTheFieldMessagesComeThrough', () => {
    const error = toApiError(responded(400, {
      message: 'Validation failed',
      fieldErrors: { title: 'must not be blank' },
    }));

    expect(error.fieldErrors).toEqual({ title: 'must not be blank' });
  });

  it('GivenFieldErrorsThatAreNotMessages_WhenItIsNormalised_ThenTheyAreDropped', () => {
    // Parsing untrusted input: a nested object where a string belongs would otherwise be rendered.
    const error = toApiError(responded(400, { message: 'Nope', fieldErrors: { title: { nested: 1 } } }));

    expect(error.fieldErrors).toBeUndefined();
  });

  it('GivenNoResponseAtAll_WhenItIsNormalised_ThenItSaysSoAndOffersNoTraceId', () => {
    // A network failure or a timeout: nothing on the server ever saw the request, so there is no id
    // to quote and offering one would send somebody searching a log for something that is not in it.
    const error = toApiError(new AxiosError('Network Error', 'ERR_NETWORK', config, {}));

    expect(error.message).toMatch(/could not reach the server/i);
    expect(error.traceId).toBeUndefined();
    expect(error.status).toBeUndefined();
  });

  it('GivenAProxyReturningHtml_WhenItIsNormalised_ThenItIsStillSafeToRender', () => {
    const error = toApiError(responded(502, '<html><body>Bad Gateway</body></html>'));

    expect(error.status).toBe(502);
    expect(error.message).toBe('Something went wrong.');
  });

  it('GivenSomethingThatIsNotAnAxiosError_WhenItIsNormalised_ThenItsMessageIsKept', () => {
    expect(toApiError(new Error('boom')).message).toBe('boom');
    expect(toApiError('a string').message).toBe('Something went wrong.');
  });

  it('GivenAnEmptyTraceId_WhenItIsNormalised_ThenItIsAbsentRatherThanBlank', () => {
    // So a caller can test for the id without also testing for ''.
    const error = toApiError(responded(500, { message: 'Internal server error', traceId: '' }));

    expect(error.traceId).toBeUndefined();
  });
});

/**
 * What a form actually shows.
 *
 * A 400 puts "Validation failed" in `message` and the reason in `fieldErrors`, so a page that renders
 * `message` tells somebody that something is wrong and nothing about what. That is what happened to
 * the 400s BR-16 and BR-17 introduced: the API named the field and the interface dropped it.
 */
describe('toFormMessage', () => {
  it('GivenAFailureOnTheFormsMainField_WhenItIsDescribed_ThenThatMessageIsShownAlone', () => {
    const message = toFormMessage(
      { status: 400, message: 'Validation failed', fieldErrors: { title: 'size must be between 0 and 255' } },
      'title',
    );

    expect(message).toBe('Size must be between 0 and 255');
  });

  it('GivenFailuresOnFieldsTheFormDidNotName_WhenTheyAreDescribed_ThenEachIsNamedAndOrdered', () => {
    // Sorted so the same failure always reads the same way, whatever order the map arrived in.
    const message = toFormMessage({
      status: 400,
      message: 'Validation failed',
      fieldErrors: { password: 'size must be between 8 and 72', name: 'must not be blank' },
    });

    expect(message).toBe('Name: must not be blank. Password: size must be between 8 and 72.');
  });

  it('GivenAFieldFailureElsewhere_WhenTheFormsOwnFieldIsUnaffected_ThenTheOtherFieldIsStillNamed', () => {
    // The primary field is a preference, not a filter: dropping the rest would show an empty error.
    const message = toFormMessage(
      { status: 400, message: 'Validation failed', fieldErrors: { icon: 'size must be between 0 and 100' } },
      'name',
    );

    expect(message).toBe('Icon: size must be between 0 and 100.');
  });

  it('GivenAFailureWithNoFieldDetail_WhenItIsDescribed_ThenTheTraceIdIsAppendedForAReport', () => {
    const message = toFormMessage({ status: 500, message: 'Internal server error', traceId: 'abc123' });

    expect(message).toBe('Internal server error (reference abc123)');
  });

  it('GivenAFieldFailure_WhenItIsDescribed_ThenNoTraceIdIsAppended', () => {
    // A trace id helps a bug report and clutters a form: the user can fix this one themselves.
    const message = toFormMessage({
      status: 400,
      message: 'Validation failed',
      fieldErrors: { title: 'must not be blank' },
      traceId: 'abc123',
    }, 'title');

    expect(message).toBe('Must not be blank');
  });

  it('GivenAFailureWithNeitherFieldsNorATraceId_WhenItIsDescribed_ThenTheMessageStandsAlone', () => {
    const message = toFormMessage({ message: 'Could not reach the server. Check your connection and try again.' });

    expect(message).toBe('Could not reach the server. Check your connection and try again.');
  });
});
