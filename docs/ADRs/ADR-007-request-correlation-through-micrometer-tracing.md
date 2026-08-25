# ADR-007: Correlation ids come from Micrometer Tracing, not from a hand-rolled request id

- **Status:** Accepted — superseded in part by [ADR-010](ADR-010-structured-logging-and-a-level-policy.md)
- **Date:** 2026-08-24
- **Scope:** `backend/` — logging, the error wire contract, the STOMP channels and the scheduled jobs.
  One new compile dependency. No change to any domain type.

## Context

[ADR-006](ADR-006-framework-exceptions-through-responseentityexceptionhandler.md) made the advice log an
unexpected 5xx at ERROR with its stack trace. That improved nothing a user could act on: the error body
carries `status`, `message`, `path` and a zone-less timestamp, so "I got a 500 on `/api/upgrades`"
matches every 500 that endpoint ever produced. The trace existed and could not be found.

Nothing in the backend emitted a correlation id at all — no MDC key, no response header, no field on any
response. The five log statements in `src/main` were anonymous, including the DEBUG line
`SpringDomainEventPublisher` writes for every domain event, which is the only thing the scheduled jobs
log and which fires every minute from `dispatchReminders`.

Two shapes of solution were on the table, and they differ in wire format, not just in code.

## Decision

Add `io.micrometer:micrometer-tracing-bridge-otel` and let Micrometer Tracing own the id.

Most of the behaviour follows from the dependency alone, which is the main reason it wins on effort as
well as on standards compliance:

- `spring-boot-actuator-autoconfigure` registers `LogCorrelationEnvironmentPostProcessor`, and Boot's
  `logging/logback/defaults.xml` already interpolates `${LOG_CORRELATION_PATTERN}` into the stock
  console pattern. Every log line gains `[<traceId>-<spanId>]` with **no `logback-spring.xml` and no
  `logging.pattern.*`**.
- Inbound W3C `traceparent` is consumed by default, so a caller's trace is continued rather than
  replaced.
- `ServerHttpObservationFilter` opens the scope in a try-with-resources, so the MDC is cleared when the
  request ends whether or not it failed.

Four things had to be written:

1. **`X-Trace-Id` on the response**, set by a filter *before* the chain runs. Ours is not a standard
   name: W3C defines `traceparent` for the request side, and its response-side counterpart
   `traceresponse` is a Level 2 draft that nothing consumes. `X-Trace-Id` carries the bare 32-character
   id — the thing someone pastes into a log search.
2. **`traceId` on `ErrorResponse`**, because the body is what a user screenshots. It is absent rather
   than null when nothing is traced, so a client is never handed an id that matches no log line.
3. **Scheduled-task observations.** Spring Framework 6.1's `ScheduledMethodRunnable` already wraps every
   `@Scheduled` invocation in an observation and closes the scope in a `finally`, but it needs an
   `ObservationRegistry` and **Boot 3.2.5 never supplies one** (no call to `setObservationRegistry` in
   either autoconfigure jar). One `SchedulingConfigurer` covers all three jobs and every job added
   later, so no scheduler class is touched.
4. **A STOMP `ExecutorChannelInterceptor`.** `ExecutorSubscribableChannel.sendInternal` only dispatches,
   so `preSend`/`afterSendCompletion` bracket the dispatch and not the handling; the handler runs on a
   channel-executor thread bracketed by `beforeHandle`/`afterMessageHandled`. Both halves get a scope,
   joined by the sending span's `TraceContext` carried in a non-native message header.

**Sampling is 1.0.** The id reaches the MDC either way — `Slf4JEventListener.onScopeAttached` writes it
without consulting the sampler — but on an unsampled span every `span.tag`/`span.error` is silently
discarded, and with no exporter a lower rate saves nothing.

**No exporter is added.** The goal is log correlation; Boot composes an empty exporter list into a
no-op.

## Consequences

**Easier.** Correlation is a property of the runtime rather than of each call site: a new log statement,
controller, job or channel gets an id without its author doing anything. The wire format is a standard,
so a second service — or a proxy, or a load balancer — can join its trace to ours without agreeing on a
convention with us first.

**Harder.** The behaviour is no longer readable from this repository alone: part of it lives in
auto-configuration whose defaults can change with a Boot upgrade. `RequestCorrelationTest` boots the
real auto-configuration precisely so that such a change fails a test rather than quietly un-correlating
the logs.

A `BatchSpanProcessor` daemon thread runs with no exporter to feed. It is harmless, and it must not be
suppressed by overriding the `SpanProcessors` bean — Boot's `spanProcessors(ObjectProvider)` collects
it, so an empty override leaves the thread running *and* unwires it.

The dependency tree gains two `-alpha` OpenTelemetry artifacts (`opentelemetry-semconv`,
`opentelemetry-instrumentation-api-semconv`) that `spring-boot-dependencies` does not manage. Nothing
gates on this today; ADR-002 records why there is no backend dependency audit.

**An inbound trace id is caller-controlled, and there is no trusted-proxy boundary.** `/api/auth/**` and
`/ws/**` are `permitAll`, so anyone can send a `traceparent` of their choosing — pinning many requests to
one id, or reusing an id read from someone else's `X-Trace-Id`. A log search then returns two people's
lines under one id. This is the cost of honouring an inbound id at all, which the issue explicitly asked
for, and it is the normal trade-off in distributed tracing: a trace id is a correlation hint, never
evidence. It is not an authorization input anywhere and must not become one. *Revisit when* this service
sits behind a proxy that can strip client-supplied headers, at which point inbound propagation should be
accepted only from that proxy.

**What this does not close.** An anonymous request to a protected endpoint is rejected inside the
Spring Security chain and never reaches `GlobalExceptionHandler`, so it returns Boot's default error
body rather than `ErrorResponse` — it carries the header but not the body field. It is also a **403**,
not a 401: with no `AuthenticationEntryPoint` configured, Spring Security's `Http403ForbiddenEntryPoint`
answers, which is worth knowing before anyone writes a client that branches on 401. And `ServerHttpObservationFilter` is registered for
`DispatcherType.REQUEST` and `ASYNC` but not `ERROR`, so anything logged during a container error
dispatch to `/error` has no id. Nothing logs there today.

## Alternatives considered

- **A hand-rolled `OncePerRequestFilter` writing an `X-Request-Id` into the MDC.** This is what
  [issue #43](https://github.com/NaimElijah/UpHealther/issues/43) sketched, and it is less code for the
  HTTP path. Rejected because it invents a private convention where a standard exists, and the standard
  is what the engineering guidelines ask for. It also would not have covered the scheduled jobs or the
  STOMP threads without writing all of that by hand. *Revisit if* the tracing dependency ever has to be
  removed, at which point roughly 60 lines replace it for HTTP only.
- **Honouring an inbound `X-Request-Id` as the id itself.** Not possible: an OpenTelemetry trace id is
  32 lowercase hex characters, and a dashed UUID or an nginx `$request_id` cannot become one. Carrying
  one alongside as baggage under a second MDC key is a larger piece of work with no current caller
  asking for it. *Revisit when* a proxy in front of this service sets `X-Request-Id` and someone needs
  to join the two.
- **`@Observed` with `spring-boot-starter-aop` for the scheduled jobs.** Rejected: a new starter, a new
  property (`management.observations.annotations.enabled`), AOP proxies around two components and an
  annotation on three methods, to achieve strictly less than one `SchedulingConfigurer` line.
- **A `TaskDecorator` on the STOMP channel executors** instead of an interceptor. Vendor-neutral and it
  avoids touching message headers, but `ChannelRegistration` only lets you supply a whole executor, so
  taking this route means silently forking Spring's default pool sizing for those channels.
- **Adding an OTLP exporter now.** Rejected as premature: there is no collector to send to, and the
  decision of where traces go is a deployment decision this project has not had to make. *Revisit when*
  there is somewhere to export to — at which point sampling at 1.0 is the volume that starts leaving
  the process, and that number should be reconsidered deliberately.
