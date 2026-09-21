# ADR-018: A content security policy that hashes the one inline script rather than allowing inline scripts

- **Status:** Accepted
- **Date:** 2026-09-21
- **Scope:** `frontend/nginx.conf` — the headers served with the document — and a test that keeps the
  policy and the script it permits in step. No application code changes, no new dependency.

## Context

nginx served the SPA with no security headers at all. Spring sends `nosniff` and frame options on
`/api`, which protects the JSON; nothing protected the *page*, which is where a script would run.

The work in ADR-015 makes this worth doing now rather than later. The refresh credential was moved into
an `HttpOnly` cookie specifically so injected script cannot read it, and the access token was moved out
of web storage for the same reason. Both of those defend against script that is already running. A CSP
is the layer that tries to stop it running at all, and shipping the first two without the third would
be defending the valuables and leaving the door open.

One thing makes the policy awkward. `index.html` contains an inline script — the pre-paint theme
setter — and it **has** to be inline: it must run synchronously during head parse so the `dark` class
is on `<html>` before the first pixel. A deferred module script paints light first and corrects itself
a frame later, which is the flash the script exists to prevent. So the policy must permit exactly one
inline script.

## Decision

**A hash, not `'unsafe-inline'`.** `script-src 'self' 'sha256-…'`, where the hash is of the boot
script's exact text. `'unsafe-inline'` would permit that script and every script an attacker manages to
inject beside it, which is the entire thing being defended against — it would be a policy that reads
like a defence and is not one.

**The full policy**, each directive closing something a default leaves open:

| Directive | Why |
|---|---|
| `default-src 'self'` | Everything not named below comes from this origin. |
| `script-src 'self' 'sha256-…'` | The bundle, plus exactly one inline script. |
| `style-src 'self'` | Tailwind ships a real stylesheet; no inline styles are needed. |
| `img-src 'self' data:` | `data:` because the build inlines small assets. |
| `font-src 'self'` | No third-party font CDN, so no third party learning who reads the page. |
| `connect-src 'self' ws://$http_host wss://$http_host` | The STOMP socket to `/ws`. `$http_host` rather than a literal, so it works on localhost and a real domain unedited. |
| `object-src 'none'` | Nothing uses plugins, and `<object>` is a classic injection sink. |
| `base-uri 'self'` | Stops an injected `<base>` silently repointing every relative URL, including the bundle. |
| `form-action 'self'` | A form cannot be made to post credentials elsewhere. |
| `frame-ancestors 'none'` | Not embeddable — the thing that actually stops clickjacking. |

**Every header is marked `always`.** Without it nginx omits them on error responses, so the 404 and 5xx
pages — the ones most likely to be rendering something unexpected — would ship with no policy.

**Beside the CSP:** `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` (the coarser form of
`frame-ancestors`, for browsers without CSP Level 2), `Referrer-Policy: strict-origin-when-cross-origin`
— a path here can name a health area or an upgrade, which is the user's own words about their health
and has no business in a third party's logs — and a `Permissions-Policy` denying camera, microphone and
location, none of which this application uses. `server_tokens off` stops advertising the nginx version.

**The hash is recomputed by a test, not trusted.** `bootScriptCsp.test.ts` reads the shipped
`index.html` and `nginx.conf`, hashes the script, and fails if the policy does not already contain that
digest. Without it the failure mode is quiet and specific: edit the boot script, the browser silently
refuses to run it, the page flashes the wrong theme — on production only, because the dev server serves
no CSP at all — and nothing in the suite notices.

**The hash is computed over LF line endings.** A CSP hash covers the script's bytes, so line endings
are part of it. `.gitattributes` declares `* text=auto eol=lf`, so the repository, every fresh checkout
and the Docker build all hold LF; a working tree that predates that declaration can still be CRLF on
disk, and hashing those bytes would produce a digest matching nothing anybody serves.

## Consequences

**Easy.** An injected `<script>` does not execute, an injected `<base>` cannot repoint the bundle, the
page cannot be framed, and paths stop leaking to third parties in a `Referer`. Editing the boot script
without updating the policy is now a build failure instead of a production-only flash.

**Hard.** The policy is in nginx, so it is absent from `npm run dev` — the dev server serves no headers
at all, and a violation is only visible in a compose run or in production. Adding a third-party script,
font or image source now means editing the policy, which is the intended friction and will still be
somebody's confusing afternoon. And the inline script is permitted by a hash of its exact text, so
reformatting it — even a whitespace change — breaks it; the test catches that, but the first reaction
to the failure will be surprise.

## Alternatives considered

**`'unsafe-inline'` in `script-src`.** Rejected: it permits every injected script, which makes the
policy theatre. It is also the path of least resistance and the reason this ADR spells out the
alternative at all. **Revisit** never.

**A nonce instead of a hash.** A nonce is the better mechanism where the server renders the page — it
survives edits to the script. Rejected here because nothing renders this page: nginx serves a static
file, so a nonce would have to be injected per request with `sub_filter` or similar, turning a static
file server into a templating one. **Revisit** if the SPA ever gains server-side rendering.

**Moving the theme script into the bundle to avoid the inline script entirely.** Rejected: that is
precisely the change ADR-005's flash-of-wrong-theme work undid. A bundled script is deferred, so the
page paints light first. **Revisit** never; the whole point of the script is that it runs before paint.

**Setting the headers in Spring instead of nginx.** Rejected: these are properties of the *document*,
and the document is served by nginx without touching Spring at all. Spring already sets the headers
that belong to `/api`.

**Adding HSTS (`Strict-Transport-Security`).** Deliberately not set. It is the right header for a TLS
deployment, and this compose stack serves plain HTTP on localhost, where it would either do nothing or
— if a developer ever browsed to `localhost` over HTTPS once — pin their browser to HTTPS for a host
that does not serve it. **Revisit** as part of putting this behind TLS, which is where the certificate,
the redirect and the `max-age` are decided together.
