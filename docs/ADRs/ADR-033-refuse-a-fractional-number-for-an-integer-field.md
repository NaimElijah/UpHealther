# ADR-033: Refuse a fractional number sent for an integer field

- **Status:** Accepted
- **Date:** 2026-10-14
- **Scope:** how every JSON request body binds a number to an integer field. One setting in
  `backend/src/main/resources/application.yml`. It departs from one of the Jackson 3 defaults that
  [ADR-032](ADR-032-the-backend-runs-on-spring-boot-4-1.md) adopted.
- **Issue:** [#240](https://github.com/NaimElijah/UpHealther/issues/240)

## Context

BR-19 says a rating is "a whole number from 1 to 5". A request could send `"rating": 3.7`, and the
API stored `3` without a word.

- **Jackson coerces it.** `DeserializationFeature.ACCEPT_FLOAT_AS_INT` is on by default, in Jackson 2
  and in Jackson 3.1.5, the version Boot 4.1.1 manages. A JSON float bound to an `Integer` is truncated
  toward zero (`StdDeserializer._parseInteger` calls `getValueAsInt()`).
- **Validation cannot see it.** `@Min(1) @Max(5)` checks the value after binding. By then it is the
  truncated `3`, which passes.

The same applies to every integer in a request body:

- `ProgressRequest.rating`;
- `ReflectionRequest.difficultyRating` and `benefitRating`;
- `HealthAreaRequest.priority`.

The interface never sends a fraction:

- The rating buttons send whole numbers.
- The reflection form parses with `parseInt`.
- The health-area form refuses a fractional priority itself. It does that because the API used to
  truncate one.

So the gap was open only to a caller outside the interface. It still breaks a stated rule: what is
stored is a value the caller never sent.

[ADR-032](ADR-032-the-backend-runs-on-spring-boot-4-1.md) chose "Jackson 3 with its own defaults",
after a comparison found that the 3.2.5 and 4.1.1 builds answered identically. That comparison covered
what the API returns. It did not cover what the API accepts, and the coercion was the same in both
builds.

## Decision

**A JSON number with a fractional part, sent for an integer field, is refused.**
`spring.jackson.deserialization.accept-float-as-int: false` is set in `application.yml`, so Boot's
`JsonMapper` throws instead of truncating. The body cannot be bound, and
`GlobalExceptionHandler.handleHttpMessageNotReadable` answers `400` "Malformed request body", as it does
for any unbindable body
([ADR-006](ADR-006-framework-exceptions-through-responseentityexceptionhandler.md)).

In Boot 4.1 the property is still `spring.jackson.deserialization`, a map keyed by
`tools.jackson.databind.DeserializationFeature`. It did not move under `spring.jackson.json.*`, which
holds only the parser's and generator's read and write features.

`ProgressControllerTest`, `ReflectionControllerTest` and `HealthAreaControllerTest` each send a fraction
and expect `400` with nothing stored. The `@WebMvcTest` slice uses Boot's property-configured mapper, so
these tests check the setting itself, not a copy of it.

## Consequences

- **BR-19 now holds for every caller**, not only for the interface. So does the whole-number priority
  the health-area form already asks for.
- **It is global.** Any integer field added later is refused the same way, with nothing to remember.
- **`3.0` is refused too.** Jackson reads it as a float token, and the setting does not look at the
  value. The interface cannot send one, because `JSON.stringify(3.0)` writes `3`. A client of its own
  that writes `3.0` would be refused.
- **The refusal names no field.** The body says "Malformed request body", with no `fieldErrors`,
  because ADR-006 withholds the parser's message: it names Java types and byte offsets. An
  out-of-range whole number still answers with a `fieldErrors` entry. That is why the health-area form
  keeps checking a priority itself: there, the user is told which field is wrong.
- `Double` fields, `ProgressRequest.numericValue` and `TrackingConfigRequest.targetNumericValue`, are
  unaffected.

## Alternatives considered

- **Refuse it per field, with a custom deserializer on each integer field.** Rejected. Every integer
  field would need the annotation, and a field added later without it would truncate silently again.
  `@JsonFormat(lenient = false)` is not a lighter option either, because this path never consults it,
  only the coercion configuration.
  - **Revisit** if an integer field ever has to accept a fraction, for example to round rather than
    refuse it. That field would opt out with its own deserializer.
- **Keep the default and validate the raw value.** That means binding ratings as `BigDecimal` or
  `Double` and checking that the scale is zero. Rejected. It changes the request records' types for
  every caller to undo something a single setting prevents, and the integer type is the honest
  description of the field.
  - **Revisit:** never, while a setting does the same.
- **Name the field in the 400**, by reading the binding path from Jackson's `MismatchedInputException`
  in `handleHttpMessageNotReadable`. Not done here. It changes what ADR-006 decided to withhold, and the
  interface has no path that reaches this refusal.
  - **Revisit** when a client outside the interface needs to know which field it got wrong. A public
    API (§5.4 of the requirements says there is none) would be that client.

## When to revisit

- **Jackson changes the default, or Boot renames the property.** The three tests above fail if the
  setting stops applying.
- **A field needs to accept a fraction.** See the first alternative.
