# ADR-042: The jar carries no JWT signing key — a run from the checkout reads one from `backend/config/`

- **Status:** Accepted — its "if #84 introduces such a signal for the demo seed" trigger did not
  fire: [ADR-085](ADR-085-the-jar-creates-no-account-of-its-own.md) names the seed's Flyway callback
  location in the same unpackaged `backend/config/application.yml`, a setting whose absence is the
  deployment's behaviour, not an environment signal
- **Date:** 2026-10-17
- **Scope:** `application.yml`, `backend/config/application.yml`,
  `backend/src/test/resources/config/application.yml`, `docker-compose.yml`, `.env.example`,
  `.github/workflows/ci.yml`.
- **Issue:** [#87](https://github.com/NaimElijah/UpHealther/issues/87), the `JWT_SECRET` half, and
  [#285](https://github.com/NaimElijah/UpHealther/issues/285). The cookie half is
  [ADR-041](ADR-041-the-refresh-cookie-is-always-secure.md).

## Context

`application.yml` bound `app.jwt.secret` to `${JWT_SECRET:<a published value>}`. The fallback let a
clone run with no setup. It also meant a deployment that forgot `JWT_SECRET` signed every token with
a key anyone can read in the repository. The value is longer than 256 bits, so the `Keys.hmacShaKeyFor`
check behind NFR-3 accepted it.

A known key is worse than a guessable one here. `BearerTokenAuthenticator` confirms that a token's `sid`
names a live session, but not that the session belongs to the token's `sub`. So an attacker with the
key could:

1. register;
2. sign in, to get a live `sid` of their own;
3. mint a token naming any other account's id with that `sid`.

Account ids are not secrets: NFR-6 allows them in log lines.

The development key was also spelled four ways (#285):

- `application.yml`;
- `docker-compose.yml`'s default;
- `.env.example`;
- the CI step's environment.

A token minted by a native run was refused by a compose run of the same checkout.

The cookie half of #87 was solved by removing a setting (ADR-041). That route is closed here: a signing
key cannot have one right value for everybody. So the key has to come from the environment of the run,
and a run that supplies none has to fail.

## Decision

**The jar has no signing key.** `app.jwt.secret` is `${JWT_SECRET:}`. The empty default binds `""`,
which fails `JwtProperties`' `@NotBlank` at startup, and the failure names the property:

```
Binding to target com.healthupgrades.common.security.JwtProperties failed:
    Property: app.jwt.secret
    Value: ""
    Reason: must not be blank
```

The empty default is deliberate. A bare `${JWT_SECRET}` is not an error to Boot's binder: an
unresolvable placeholder binds its own text, `${JWT_SECRET}`. That would fail later as a weak key, and
a longer variable name would not fail at all.

**A run from the checkout reads a development key from `backend/config/application.yml`.**

- **What it is.** That file holds the one published local-development key, as
  `${JWT_SECRET:<that key>}`.
- **How it is found.** Spring Boot reads `./config/application.yml` relative to the working directory by
  default, and it outranks the classpath. `./mvnw spring-boot:run` runs from `backend/`, so it finds the
  file.
- **Why a deployment never sees it.** The Dockerfile builds from `src/` and copies only the jar, so
  neither the jar nor the image contains it.
- **Why not a profile.** No profile or environment signal is involved: the file is present where a
  developer works, and absent where the image runs.

**Compose defaults `JWT_SECRET` to the same key.** The image has none, so the compose stack must supply
one to stay a one-command run. The local-development key is now spelled identically in two places,
`backend/config/application.yml` and `docker-compose.yml`. `.env.example` leaves it empty.

**Tests carry their own key** in `backend/src/test/resources/config/application.yml`.

- It is on the test classpath only, so it is never packaged.
- It sits under `config/` so that a test starts from any working directory, including an IDE launched at
  the repository root, where `backend/config/` is out of reach.
- Run from `backend/`, as Maven runs the tests, the development key outranks it.
- CI's own key is gone.

`ShippedJwtConfigurationTest` reads `classpath:/application.yml` alone, with the process environment
removed, and asserts that the application refuses to start. It also asserts that a supplied key is the
one used.

## Consequences

- **A deployment cannot sign with a published key by omission.** The packaged jar, started outside the
  checkout with no `JWT_SECRET`, refused to start with the error above.
- **Local runs still need no setup.** `./mvnw spring-boot:run` started with `JWT_SECRET` unset, and so
  did `docker compose up --build` with no `.env`. Sign-in, reload and refresh worked through both.
- **A deployment through `docker-compose.yml` still inherits the published key** unless it sets
  `JWT_SECRET`. This gap is accepted.
  - The compose file is the local stack.
  - Production is ECS on Fargate built from the image
    ([#198](https://github.com/NaimElijah/UpHealther/issues/198)), which carries no key.
  - The compose file says so beside the variable, and the README lists it under known limitations.
- **The working directory matters for a native run.**
  - `java -jar` from `backend/` would read the development key too. Nothing deploys that way.
  - An IDE that launches `HealthUpgradesApplication` from the repository root, VS Code's default
    working directory, does not read it, and the application refuses to start. Such a launch sets its
    working directory to `backend/`, or exports `JWT_SECRET`.
  - Tests run from an IDE are unaffected: they read the key on the test classpath.
- **An exported but empty `JWT_SECRET` fails the start,** even in a checkout. An empty variable counts as
  set, so it hides the development key instead of falling back to it. This fails closed, and
  `backend/config/application.yml` says so.
- **Binding a session to its token's subject is still a separate fix.** Without a published key the
  forgery above needs the real key. The check is still missing, and
  [#286](https://github.com/NaimElijah/UpHealther/issues/286) tracks it.

## Alternatives considered

- **Keep the fallback and refuse a published key outside an explicit local run** (#87's option 3).
  Rejected for the same reason as in ADR-041: it needs a new environment signal that every run path
  carries and that fails closed when missing. It would also need the published values written into the
  code that refuses them.
  - **Revisit:** if [#84](https://github.com/NaimElijah/UpHealther/issues/84) introduces such a signal
    for the demo seed.
- **Drop the fallback and let every run supply `JWT_SECRET`.** Rejected. `./mvnw spring-boot:run` and
  every test would need a variable set first, and that contradicts the one-command local run the
  repository baseline asks for.
- **Supply the key through `spring-boot-maven-plugin`'s `environmentVariables`.** Rejected. The plugin
  configuration would override a `JWT_SECRET` the developer exported. It also covers only
  `spring-boot:run`: not the tests, and not an IDE launch.
- **A `src/test/resources/application.properties`.** Rejected. A root-level file on the test classpath
  merges into every test's configuration, and it is easily mistaken for the shipped file. The
  `config/` subdirectory is equally standard, and it reads as an override.

## When to revisit

- **`docker-compose.yml` is used to deploy anything,** even a staging copy.
- **The jar is ever run from a checkout** rather than from the image.
- **A second signing key is needed** (rotation, or asymmetric keys). The key then comes from a secret
  manager ([#212](https://github.com/NaimElijah/UpHealther/issues/212)), and this file's mechanism
  should be re-examined with it.
