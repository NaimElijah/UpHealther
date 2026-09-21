# ADR-017: An in-process fixed-window rate limit per client address, not per account

- **Status:** Accepted
- **Date:** 2026-09-21
- **Scope:** `backend/` — a `HandlerInterceptor` over the two anonymous authentication endpoints — plus
  the nginx and Tomcat configuration that makes the client address trustworthy. Closes #56. No new
  dependency.

## Context

Nothing limited how fast anybody could try to sign in. BCrypt makes each attempt expensive for the
*server*, which bounds the rate somewhat, but it bounds it at "as many as the server can stand" rather
than at "as many as a person could plausibly need" — and the expense is the wrong way round: an
attacker spends a few bytes, the server spends a hash.

Two different attacks, with one shape:

- **Credential stuffing and password guessing.** Sign-in is anonymous, unlimited, and answers
  definitively.
- **Free account creation.** Registration is anonymous and unlimited, so a database fills with junk
  faster than anybody notices.

The hard part is not counting. It is deciding **what to count**, and being sure the thing being counted
cannot be chosen by the caller.

## Decision

**Count per client address, in a fixed window, in this process's memory.** Ten attempts per minute per
address on `POST /api/auth/login` and `POST /api/auth/register`. Over the limit is **429** with
`Retry-After`.

**A `HandlerInterceptor`, registered explicitly against those two paths.** Not a filter bean: Boot
registers a `Filter` `@Component` automatically *and* any chain that names it, so it runs twice and
counts one attempt as two — and it would be pulled into every `@WebMvcTest` slice in the codebase,
putting a rate limit in front of ten controller tests that have nothing to do with authentication.

**IPv6 is keyed by its /64, not by the whole address.** This is the difference between a limit and a
decoration. A residential IPv6 allocation is typically a /64 or larger, so counting whole addresses
lets one attacker walk through billions of them without ever meeting a limit. IPv4 is counted whole:
there is no equivalent block handed out as a unit, and collapsing to a /24 would put unrelated
customers of one ISP in the same bucket.

**The address must not be one the caller can choose.** This is the load-bearing half, and it is
configuration rather than code:

- nginx sets `X-Forwarded-For $remote_addr`, **not** `$proxy_add_x_forwarded_for`. The latter *appends*
  the real client to whatever the caller already sent, so a request arriving with a forged header
  reaches the backend with the forgery still leftmost.
- `server.forward-headers-strategy: native`, so Tomcat reads the header at all.
- `server.tomcat.remoteip.internal-proxies` is narrowed to nginx's address on a named compose network
  with a fixed subnet. Boot's default trusts all of `10/8`, `172.16/12` and `192.168/16` — and
  `172.16/12` contains the Docker bridge gateway, so with the default, anything reaching the published
  `:8080` directly could name whatever client address it liked and get a fresh allowance per request.

**The map is bounded** (`max-tracked-clients`, default 10 000). It is keyed by something the caller
chooses, so an unbounded one is a second denial of service delivered through the defence against the
first. At the cap, expired windows are swept first; only if that frees nothing is the oldest live
window evicted, which weakens one client's limit rather than killing the process.

**The address is never logged and never used as a metric tag.** It is personal data (NFR-6), and as a
tag it would also be unbounded, which ADR-012 forbids. The counter `auth.rate_limited` is tagged by
endpoint — a closed set of two.

## Consequences

**Easy.** Guessing a password from one address is now bounded at ten a minute, and the refusal costs no
BCrypt because the interceptor runs before the controller. A client is told how long to wait, so an
honest one stops hammering. There is no new dependency, no network hop on the request path, and nothing
to operate.

**Hard.** The limit is per instance, so running two instances doubles it — acceptable while §5.3 says
this is not horizontally scaled, and the first thing to revisit when that changes. A fixed window lets
a client spend its whole allowance at the end of one window and again at the start of the next: twice
the limit across an instant, which for ten-a-minute is not a meaningful attack. Clients behind one
corporate NAT share a bucket. And the correctness of the whole thing now depends on proxy
configuration that is easy to get wrong and silent when it is — which is why the reasoning above is
written down rather than left in a YAML comment.

## Alternatives considered

**Per-account lockout** — counting failures against the email and locking the account. **Rejected, and
it is the important rejection.** It hands anybody a way to lock any user out of their own account by
failing to sign in as them a few times: a denial of service that is *easier* than the attack being
prevented and aimed at a specific victim. It also leaks which accounts exist, because a locked account
answers differently from an unknown one. **Revisit** never in this form; if account-targeted defence is
ever wanted, it is a delay or a second factor, not a lockout.

**bucket4j.** Rejected: a dependency, and a capable one, for an algorithm that is twenty lines. Its
token-bucket smoothing is a better shape than a fixed window, but not better enough to be worth a
library on the request path. **Revisit** together with the shared store below — the two arrive
together, since bucket4j's value is its Redis/Hazelcast backends.

**Rate limiting in nginx** (`limit_req_zone`). Rejected: it is invisible to the application, so the
refusal cannot carry the API's error body or a trace id, cannot be counted in the same metrics as
everything else, and cannot be tested in this repository at all. It is also bypassed entirely by
anything reaching `:8080` directly, which is exactly the path the trusted-proxy fix above is about.
**Revisit** as a *second* layer in front of this one if volumetric abuse ever reaches the point where
refusing it in Java is itself the expense.

**A shared store (Redis) so the limit holds across instances.** Rejected as premature: there is one
instance, §5.3 says so deliberately, and this would be the application's first infrastructure
dependency — bought to make a limit exact that is currently a factor of one. **Revisit** the moment a
second instance is run, which is also when the in-memory STOMP broker needs replacing; they are the
same decision.

**A sliding window or a leaky bucket.** Rejected: strictly better behaviour at the window boundary, for
more state per client and more code. The boundary case here is "twenty attempts in two seconds, then
none for a minute", which does not matter at this limit. **Revisit** if the limit is ever lowered far
enough that doubling it at the boundary is material.
