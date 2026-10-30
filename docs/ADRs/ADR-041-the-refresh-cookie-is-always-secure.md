# ADR-041: The refresh cookie is always Secure — there is no setting to forget

- **Status:** Accepted — its "a phone on the LAN" revisit trigger now also meets
  [ADR-057](ADR-057-the-developer-stack-is-reachable-from-this-machine-only.md), which publishes the
  compose stack on `127.0.0.1` only. The cookie it describes is renamed `__Host-refresh_token`, on
  `Path=/`, by [ADR-065](ADR-065-the-backend-hardened-after-scan-05.md); the prefix requires the
  `Secure` this record fixed
- **Date:** 2026-10-17
- **Scope:** `RefreshCookies`, `RefreshCookieProperties`, `application.yml`, `docker-compose.yml`,
  `.env.example`.
- **Issue:** [#87](https://github.com/NaimElijah/UpHealther/issues/87). The signing-key half of the
  same issue is [ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md).

## Context

[ADR-015](ADR-015-server-side-sessions-behind-a-rotating-refresh-cookie.md) put the refresh credential
in an `HttpOnly; Secure; SameSite=Strict` cookie, with no caveat. The code did not match it:

- `Secure` came from `app.auth.cookie.secure`, bound to `${AUTH_COOKIE_SECURE:false}`.
- `docker-compose.yml` and `.env.example` both defaulted it to `false` too.
- The only reason given was that a developer on `http://localhost` has no TLS.

So every default shipped the cookie without `Secure`, and nothing failed when a deployment kept that
default. The credential is good for up to 30 days. Without `Secure`, two things go wrong:

- **It is sent over plain HTTP.** An on-path attacker can redirect a first `http://` visit to
  `/api/auth/refresh`, and the browser attaches the cookie. `SameSite=Strict` does not stop that,
  because it is a same-site navigation.
- **It can be overwritten from plain HTTP.** Browsers refuse to let an insecure origin replace a
  `Secure` cookie, and only a `Secure` one. Without the flag, an attacker can plant their own
  credential.

NFR-36 carried this as a Known deviation. `AuthControllerTest` could not catch it, because its slice
builds the cookie with `secure` hard-wired to `true`.

The local-development reason no longer holds. MDN (`Set-Cookie`) says the `https:` requirement is
ignored when a `Secure` cookie is set by `localhost`. Checked on 2026-10-17 against this branch:
sign in at `http://localhost:3000` (Vite, proxying to `./mvnw spring-boot:run`), then reload.

| Browser | Cookie stored as `Secure` | Sent back on reload; `/api/auth/refresh` answers 200 |
|---|---|---|
| Chrome 155.0.8059.41 | yes | yes |
| Edge 155.0.4283.45 | yes | yes |
| Firefox 157.0 (Playwright 1.64.1's build) | yes | yes |
| Safari | **not verified** — no macOS machine was available | — |

## Decision

**The refresh cookie is always `Secure`, and the attribute is not configurable.**

- `RefreshCookies` sets `.secure(true)`.
- `RefreshCookieProperties` keeps only `name` and `path`.
- `AUTH_COOKIE_SECURE` is gone from `application.yml`, compose and `.env.example`.

`HttpOnly`, `Secure` and `SameSite=Strict` are now all fixed in one place, for the reason
`RefreshCookieProperties` already gave for the other two: a deployment that could turn one off is a
deployment where somebody eventually will.

`AuthSessionFlowIT` asserts `Secure` on the cookie the assembled application issues with its shipped
configuration, so a configurable default cannot return unnoticed.

## Consequences

- **Nothing to set, so nothing to forget.** ADR-020 makes the same argument for the server's time zone.
- **ADR-015's description is now true** without a caveat.
- **Local development is on `localhost`.** The waiver MDN describes is for `localhost`. A LAN address
  (testing from a phone) or a hosts-file name over plain HTTP is an insecure origin, so the browser
  drops the cookie and a session lasts one access token, 15 minutes.
  - The CORS allow-list already names `http://localhost:3000` alone. A sign-in from
    `http://127.0.0.1:3000` was answered 403 under compose, before any cookie was set.
  - So this narrows nothing that worked before.
- **Safari is unverified.** If it refuses the cookie on `http://localhost`, a Safari user running the
  stack locally signs in but loses the session on reload.

## Alternatives considered

- **Keep the setting, default it to `true`.** Rejected. It fixes `./mvnw spring-boot:run` only. Compose
  carried its own `:-false`, and a variable that exists can still be set to `false`.
  - **Revisit:** if a supported browser refuses a `Secure` cookie on `http://localhost`.
- **Keep the setting, refuse to start when it is `false` outside an explicit local run** (#87's option
  3). Rejected. It needs a new environment signal, such as a `local` profile, carried by compose,
  `spring-boot:run`, the slices, the ITs and CI, and it must fail closed when the signal is missing.
  That is more machinery than an attribute with one correct value deserves.
  - **Revisit:** if local development must run over a non-`localhost` origin, or if
    [#84](https://github.com/NaimElijah/UpHealther/issues/84) introduces such a signal anyway.
- **Derive `Secure` from the request** (`request.isSecure()`, through `X-Forwarded-Proto`). Rejected: it
  fails open in the likeliest topology. `frontend/nginx.conf` sets `X-Forwarded-Proto $scheme`, so
  behind a TLS terminator in front of nginx the value is `http`, and the cookie silently loses `Secure`.

## When to revisit

- **A supported browser refuses a `Secure` cookie set by `http://localhost`.** Safari is the one not
  checked.
- **Local development needs a host other than `localhost`,** such as a phone on the LAN.
