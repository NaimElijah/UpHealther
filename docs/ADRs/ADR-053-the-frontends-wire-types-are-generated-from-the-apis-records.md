# ADR-053: The frontend's wire types are generated from the API's records

- **Status:** Accepted
- **Date:** 2026-10-24
- **Scope:**
  - `backend/src/test/java/com/healthupgrades/contract/FrontendWireTypes.java`,
    `FrontendWireTypesContractTest.java` and `FrontendWireTypesJsonTest.java`;
  - `frontend/src/types/api.generated.ts`, which they write, and `frontend/src/types/index.ts`, which
    derives the frontend's names from it;
  - JSpecify's `@Nullable` on the components of the request and response records under
    `adapter/in/web`;
  - the root `package.json`'s `generate:wire-types`, and `frontend/.prettierignore`;
  - NFR-10 in `docs/requirements/requirements.md`.
- **Issue:** [#168](https://github.com/NaimElijah/UpHealther/issues/168), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It replaces
  `FrontendEnumContractTest`, which checked the enums alone, and leaves calls typed by operation to
  [#328](https://github.com/NaimElijah/UpHealther/issues/328). It extends
  [ADR-009](ADR-009-test-levels-boundaries-and-naming.md) and
  [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md).

## Context

- **The frontend's copies of the API's shapes were written by hand.** `frontend/src/types/index.ts`
  declared 28 wire types. Only the eight enum unions were checked, by `FrontendEnumContractTest`.
  Field names, types, optionality and nullability drifted silently:
  - **#117:** `rating?: number` described a key the API never omits. It sends `null`, so a guard
    against `undefined` let the null through, and every entry without a rating showed "⭐ /5".
  - **#88:** the frontend had no type for `PlanRequest`, so nothing connected the Plan call to the
    body the endpoint requires.
  - **When #168 was taken up, about a dozen more:** five response types declared `?:` where the wire
    sends `null`, `HealthArea` had no `updatedAt`, `HealthUpgrade.difficulty` was required but is
    nullable, and a PUT whose `name` is `@NotBlank` was sent a `Partial<…>`.
- **The wire's nullness is a fact the Java type does not state.** Jackson writes a null rather than
  omitting the key, because no `default-property-inclusion` is configured, and it binds an absent
  request key as null. Whether a `String` or an `Integer` component is ever null depends on its
  mapper, its entity and its column, and no type says so.
- **#168 recommended OpenAPI, gated on a spike.** The plan was springdoc to produce a document, then
  openapi-typescript to turn it into types, provided springdoc could type `ProgressDto.rating` as
  `number | null` without per-field annotations. Reading the generator answered that.
  - springdoc-openapi **3.1.1**, the release built on Spring Boot 4.1, ships swagger-core
    **2.2.55**.
  - Its `ModelResolver` marks a property `required` only for a declaration annotation named
    `NotNull`, `NonNull`, `NotBlank` or `NotEmpty`, for `@Schema(requiredMode = REQUIRED)`, or for
    `@JsonProperty(required = true)`. Every response field would have come out as `field?: T`: #117's
    shape.
  - It recognises nullability by an annotation's simple name, `Nullable`, among the member's
    declaration annotations. JSpecify's `@Nullable`, Spring Framework 7's own vocabulary, is
    `@Target(TYPE_USE)`, so swagger-core does not see it.
  - So OpenAPI would have needed a custom `ModelConverter` that walks each record's components to set
    `required` and `null`, which is the whole of a generator's reflection. On top of that it would have
    added two dependency trees and a committed `openapi.json` with its own drift check. openapi-typescript
    7.13.0 also declares a peer of `typescript ^5.x`, while Dependabot's #320 proposes TypeScript 7.
- **JSpecify 1.0.0 is already on the compile classpath.** spring-core 7 depends on it at compile scope.

## Decision

### A backend test renders the TypeScript from the records

`FrontendWireTypes` (test sources) finds every `@RestController` under `com.healthupgrades` with
Spring's classpath scanner, so no context starts and the check stays in `./mvnw test` (NFR-11).

- **What it renders.** For each `@RequestMapping` handler, it takes the `@RequestBody` parameters and
  the return type, unwrapping `ResponseEntity` and skipping an empty body, then walks every record and
  enum they reach. A record becomes an `interface` under its simple name, and an enum a union of its
  constant names.
- **Scalars.**
  - `String`, `UUID`, `LocalDate`, `LocalDateTime`, `LocalTime` and `Instant` become `string`.
  - Numbers become `number`, and booleans `boolean`.
- **Containers.** A collection becomes `T[]`, and a map keyed by a `String`, a `UUID` or an enum
  becomes `Record<string, V>`.
- **Nullness.**
  - A component whose type carries `org.jspecify.annotations.Nullable` admits `null`.
  - A response declares every key, because the wire always carries it.
  - A request may leave out a key that admits null, and must carry every other one.
- **The table is closed. Each of these is a failure, not a guess:**
  - a type outside the mapping, named by the path that reached it;
  - a record that is both a request and a response;
  - two wire types with one simple name;
  - any Jackson annotation on a record, a component or an enum constant;
  - a request component of reference type that is neither `@Nullable` nor constrained by `@NotNull`,
    `@NotBlank` or `@NotEmpty`.
- **The output** is `frontend/src/types/api.generated.ts`: a header, the enums, then the interfaces,
  each group in name order, with LF endings.

`FrontendWireTypesContractTest` fails when the committed file differs from the render.
`npm run generate:wire-types` runs the same test with `-Dwire-types.write=true`, which writes the file
instead. It is a root script because that one spelling works in PowerShell, Git Bash and `sh`, as
ADR-044 requires.

### Boot's own mapper is held to the same rules

The table above is the generator's model of Jackson, and Jackson can depart from it without a line of
the record changing:

- Jackson 3 writes and reads an enum through its `toString()` by default.
- An extra `getX()` on a record is written as a key, and a `@JsonValue` replaces the whole object.
- A module or a mixin can reshape anything.
- One `spring.jackson.default-property-inclusion` drops every null key a response declares present.

`FrontendWireTypesJsonTest`, a `@JsonTest`, takes every record the generator rendered and checks it
with the mapper the application runs with:

- **Each response is written twice.** Filled in, it must produce exactly its components as keys, each
  of the JSON kind its type is rendered as, with each enum spelled as its constant's name. With its
  `@Nullable` components unset, every key must still be there, holding `null`.
- **Each request is read twice.** Once from JSON spelled exactly as its generated interface says, and
  once with its optional keys left out. Both must bind to the same values.

### The frontend's names derive from the generated file

`index.ts` keeps every name the frontend already imports, so no import site changes. Each one is an
alias of a generated type: `HealthUpgrade` of `UpgradeDto`, `AuthResponse` of `TokenPair`, and so on.
The enum unions are re-exported. Only what the API does not send stays hand-written, and it is derived
from a generated type: `HealthArea`'s client-side `upgradeCount`, `ActionTarget`, and the URL-only
`upgradeId` of two request types. `npx tsc` then checks every consumer against the shape the API
declares.

### The rules a wire record follows

- A component that can be null on the wire carries `@Nullable`, judged from its mapper, its entity
  and its column.
- A request component is either `@Nullable` or carries a not-null constraint.
- No Jackson annotation shapes a wire record's JSON. A record crosses in one direction, and its simple
  name is unique among wire types.

`FrontendEnumContractTest` is retired. Its subject, the hand-written unions, no longer exists, and the
render produces the unions from the enums themselves. Its reverse guard, that `index.ts` declares no
screaming-caps union by hand, moves into the new test.

### The file is not Prettier's

`frontend/.prettierignore` lists the generated file. The contract test owns its bytes, and Prettier's
120-column wrapping of a long union would fight the generator on every regeneration. ESLint and `tsc`
still check it.

## Consequences

**Easy:**
- A renamed, retyped or newly nullable field fails the backend build until the file is regenerated.
  After that, `npx tsc` names every frontend use the change reaches.
- A new endpoint's body and response appear in the frontend with no hand-written type.
- No dependency is added on either side, and nothing new is audited.
- Regenerating needs a JDK, not Docker.

**Hard:**
- **A response's non-null claims are trusted.** Nothing at runtime proves that a component without
  `@Nullable` is never null. A missing annotation types a nullable field as non-null, which is #117 in
  reverse. Review is the guard, helped by the rule above that names where the evidence is.
- **A new kind of wire value stops the build** until `FrontendWireTypes` learns it: a `BigDecimal`, an
  `Optional`, a polymorphic type. So does a serializer that writes something other than the rendered
  shape, whether it is declared by annotation or registered on the mapper, because
  `FrontendWireTypesJsonTest` writes through the real mapper. That is the intended trade.
- **The mapper check proves the shape for one sample per record.** A serializer whose output depends
  on the value, such as writing large numbers as strings, would pass it.
- **A call is still paired with its path and method by hand.** A missing body or a wrong response type
  on a call is not caught by the types alone (#328).
- **A frontend-only change cannot alter a wire type.** The backend's records are the only source.
- **The STOMP payload is covered only because it reuses `NotificationDto`.** A message type of its own
  would not be reached from a controller.

## Alternatives considered

- **springdoc-openapi 3.1.1, then openapi-typescript 7.13.0** (#168's option 1). Rejected. As the
  Context records, it would need the same component-by-component nullness logic, written as a
  swagger-core `ModelConverter`, on top of two dependency trees, a second committed artifact and a
  second drift check. Its one advantage is an operation-level description of the API, which matters
  only once calls are typed by operation (#328).
- **A contract test that parses `index.ts`** (#168's option 2). Rejected. It parses TypeScript with
  regular expressions, which breaks on nesting, generics and comments. It needs a hand-kept table
  mapping `HealthUpgrade` to `UpgradeDto`. It would still need the same nullness marker, because a
  `String` that is never null looks the same as one that can be. Emitting code is far simpler than
  parsing it.
- **Leaving it to the e2e smoke suite** (#168's option 3). Rejected as the answer, kept as a
  complement. It catches drift that breaks the one journey it walks, and nothing off that path.
- **A Maven plugin or an exec goal that generates the file at build time.** Rejected. It adds build
  configuration for what the test already does in every `./mvnw test`, and a generator outside the
  test would still need the test to fail on drift.
- **Validating responses at runtime** (zod or similar). Rejected. Drift would be found in a user's
  browser instead of the build, and the bundle would gain a dependency.

## When to revisit

- **The frontend needs calls typed by operation** (#328). Method, path, body and response would come
  from one source. That is where an OpenAPI document starts to pay for itself.
- **A second client consumes the API, or the API becomes public** (requirements §5.4). A published
  description would then be part of the product.
- **The API adopts a shape the table cannot express:** polymorphic bodies, renamed keys, custom
  serializers.
- **swagger-core reads TYPE_USE `@Nullable` and treats a record's components as required.** The custom
  converter, OpenAPI's main extra cost, would then disappear.
