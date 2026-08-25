# ADR-012: Metrics are exposed as a scrape endpoint, and the actuator surface is closed by name

- **Status:** Accepted
- **Date:** 2026-08-25
- **Scope:** `backend/` — one compile dependency, the `management.*` configuration, and a `HEALTHCHECK`
  in both images. No application code.

## Context

`spring-boot-starter-actuator` has been a direct dependency since before this change — it is what
supplies the tracing auto-configuration [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md)
relies on. Everything it collects was unreachable. There was no `management.endpoints.web.exposure`
property at all, so Boot's default applied and `/actuator/health` was the only endpoint served: no
request latency, no error rate, no JVM or connection-pool saturation, and no way to read this
application's own counters. Micrometer was on the classpath with nowhere to publish to.

`management.endpoint.health.probes.enabled` was also unset, so `/actuator/health/liveness` and
`/actuator/health/readiness` did not exist. `docker-compose` polled the aggregate endpoint and neither
image declared a `HEALTHCHECK` of its own, so the `frontend` service came up as soon as the `backend`
container *started* — while Flyway was still migrating.

There is a second thing in the same area worth deciding rather than inheriting. `SecurityConfig` makes
`/actuator/**` `permitAll`, which is safe today only because Boot's default exposes one harmless
endpoint. That is safety by accident: a dependency that contributes an endpoint, or one debugging
session that widens the property and never narrows it, publishes `/actuator/env` — the resolved
configuration — or `/actuator/heapdump` to anyone who asks.

## Decision

**Add `io.micrometer:micrometer-registry-prometheus` and serve `/actuator/prometheus`.** It is
BOM-managed under Boot 3.2.5 (there is no `-simpleclient` split at this version; that arrives in 3.3),
so no version is pinned. A **pull** endpoint is the reason this is not premature in the way ADR-007
found an OTLP exporter to be: nothing has to exist for it to be useful, because it is a readable page
rather than a stream that needs somewhere to go.

**Five signals, four of them free.** Request latency and error rate
(`http_server_requests_seconds{uri,status,outcome}`), JVM and connection-pool saturation
(`jvm_memory_used_bytes`, `hikaricp_connections_pending`) come from actuator the moment a registry
exists. The application's own are `audit.events{action,outcome}` — every attempt and how it ended
([ADR-011](ADR-011-audit-as-a-log-stream.md)) — and `scheduled.job.runs{job,outcome}` with
`scheduled.job.duration{job}`, because scheduled work is what nobody is watching.

**No tag may be unbounded.** An action, an outcome, a job name and a status are closed sets; a user id,
an upgrade title and a raw path are not. This is the same reasoning `StompTracingChannelInterceptor`
already applies to span names, and it is a correctness rule rather than a style preference — a metrics
backend does not degrade gracefully when a label has a million values, and a user id is not something
anyone should be looking up there anyway. That is what the audit line is for.

**The exposure list is explicit and closed:** `health,info,prometheus`. Naming what is wanted means
nothing else can arrive unannounced, and `ActuatorEndpointsIT` asserts that seven endpoints worth
having opinions about — `env`, `heapdump`, `loggers`, `beans`, `mappings`, `configprops`, `threaddump` —
answer `404`. Widening the list is then an act that has to change a test.

**`/actuator/**` stays `permitAll`.** With the surface closed by name, what remains readable is a health
status, build info, and metric values. That is an exposure, and it is recorded here rather than waved
away: endpoint URIs and traffic volumes are visible to anyone who can reach port 8080.

**Liveness and readiness are enabled, and the health-checks poll readiness.** The two answer different
questions and an orchestrator acts on them differently: a failed liveness probe means restart the
process, a failed readiness probe means stop sending it traffic. Restarting a process that cannot reach
its database fixes nothing, which is why polling the aggregate is wrong for either. Both images now
declare a `HEALTHCHECK` so they are self-describing outside compose, with a `start_period` covering JVM
start and the Flyway migration — during which an unhealthy answer is correct. The `frontend` service
now waits for `service_healthy` rather than for the container to exist.

## Consequences

**Easier.** "Is it up, is it slow, is it failing, is the pool exhausted, did the nightly job run" are
all answerable from one endpoint, by anything that speaks Prometheus, with no code change. Adding a
counter is one `Counter.builder` call. An orchestrator can tell "restart me" from "don't route to me".

**Harder.** `/actuator/prometheus` is readable by anyone who can reach the port, and the cardinality
rule is a rule rather than a mechanism — nothing fails the build if somebody tags a counter with a user
id, and the damage shows up in the metrics backend rather than here. The exposure list is now a thing
to remember when adding an actuator-contributing dependency, which is the intended cost.

**Nothing scrapes this.** The endpoint exists and is correct and no dashboard reads it. That is the
deliberate stopping point: a Prometheus and a Grafana in `docker-compose` would be two more containers
and a dashboard to maintain for a single-instance application with no on-call and no SLO. *Revisit
when* there is a deployment somebody is paged for — at which point the endpoint is already there and
the work is the scraper, not the instrumentation.

## Alternatives considered

- **Actuator only, exposing `/actuator/metrics` and adding no dependency.** The numbers are collected
  and readable one metric at a time over JSON. Rejected because nothing can scrape that, so nobody
  would ever look: it is instrumentation that exists to be able to say it exists.
- **A separate management port (`management.server.port`), unpublished in compose.** The standard answer
  to the exposure above, and genuinely better in a real deployment — the actuator surface simply is not
  reachable from outside. Rejected here as a moving part bought too early: a second connector, a changed
  health-check target, a security-config change and a documented integration point that changes again
  the moment there is a real deployment. With the exposure list closed by name and asserted, the
  remaining surface is a status, build info and counters. *Revisit when* this runs anywhere the API port
  is reachable from outside a trusted network — that is the trigger, and it is the same one ADR-007
  names for its trusted-proxy boundary, so the two should be done together.
- **A Prometheus and Grafana stack in `docker-compose`.** Rejected: two containers and a dashboard to
  maintain for a system with no on-call, no SLO and one instance. The threshold is a deployment somebody
  is paged for.
- **An OTLP metrics exporter, matching the OpenTelemetry tracing bridge.** Vendor-neutral and consistent
  with ADR-007's choice of bridge. Rejected for the reason ADR-007 rejected the trace exporter and which
  has not changed: a push exporter needs a collector to push to, and there is not one. A pull endpoint
  has no such precondition, which is exactly why it is the one added now.
- **`management.endpoint.health.show-details: always`.** Would put the database's status in the health
  body. Rejected: `/actuator/health` is `permitAll`, and "which component is down" is information about
  the deployment's internals. The default (`when-authorized`) leaves the aggregate status readable and
  the breakdown not, which is the right split for an unauthenticated endpoint.
