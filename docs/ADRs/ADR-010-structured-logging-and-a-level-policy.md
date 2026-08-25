# ADR-010: Logs are JSON in a container and readable locally, and each level means one thing

- **Status:** Accepted
- **Date:** 2026-08-25
- **Scope:** `backend/` — the log format, the level policy and the rule about what may appear in a log
  line. One new compile dependency. No change to any domain type.
- **Supersedes in part:** [ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md), whose
  Decision states the trace id arrives "with **no `logback-spring.xml`**". There is one now. Everything
  else in ADR-007 stands, and the property it relied on is preserved deliberately — see below.

## Context

[ADR-007](ADR-007-request-correlation-through-micrometer-tracing.md) gave every log line a trace id so
that a failure a user reports can be found. It recorded, in passing, that there were **five log
statements in `src/main`**, in two classes. That number did not change afterwards.

So the correlation works and there is almost nothing to correlate. `grep` across `src/main` finds no
logger in any controller, any application service, either scheduler, any security class or any
persistence adapter. A trace id resolves, at best, to one ERROR line and its stack trace: no record of
what the request was trying to do, what state the aggregate was in, whether a transition was refused,
or how long anything took.

Two separate things were wrong, and they need separate answers. This ADR is about **the format and the
policy**; [ADR-011](ADR-011-audit-as-a-log-stream.md) is about what is recorded when state changes.

The format question is forced by where the logs go. `docker-compose` is the only deployment, so
`docker logs` is the only sink; there is no file appender and no aggregator. A line there is read by a
machine before it is read by a person, and a trace id, a level or an audit field is only queryable if
it is a *field* rather than a substring of a message. The standing engineering guideline asks for
structured logging outright.

Spring Boot 3.2.5 cannot do this on its own. Built-in structured logging
(`logging.structured.format.console`) arrived in Boot **3.4**.

## Decision

**Add `net.logstash.logback:logstash-logback-encoder` and select the format with a profile** in a new
`backend/src/main/resources/logback-spring.xml`:

- default — Boot's stock console pattern, unchanged, for a developer reading along;
- `json-logs` — a `LogstashEncoder` writing one JSON object per line. `docker-compose` sets the profile,
  so every container run is structured and no local run is.

**The file imports Boot's `defaults.xml` rather than writing its own `<pattern>`.** This is the part
worth stating explicitly, because getting it wrong is silent. `defaults.xml` defines
`CONSOLE_LOG_PATTERN`, and that pattern interpolates `${LOG_CORRELATION_PATTERN}` — the property
`LogCorrelationEnvironmentPostProcessor` fills with the trace and span id. A hand-written pattern
compiles, starts, logs happily and correlates nothing. `LogOutputFormatTest` renders one event through
the encoder the shipped file actually installs, in both formats, and asserts the id is in the output —
so the mistake fails a build instead of being discovered the next time somebody needs the logs.

**The version is pinned to 7.4 and the pin is ours to maintain.** `spring-boot-dependencies` does not
manage this artifact. 7.4 is the last release built against logback 1.3/1.4; 8.x compiles against
logback 1.5, and Boot 3.2.5 resolves logback **1.4.14**. Re-check on any Boot upgrade.

**The default level for `com.healthupgrades` moves from `DEBUG` to `INFO`,** overridable per run through
`LOG_LEVEL_APP`. `DEBUG` was the only logging configuration this project had, in the only configuration
file it has, so it was the deployment setting too — including the DEBUG line
`SpringDomainEventPublisher` writes for every domain event, which fires every minute from
`dispatchReminders`.

**Each level means one thing:**

| Level | Meaning | What is written at it |
|---|---|---|
| ERROR | actionable now, by a person | an unexpected 5xx; a `@Scheduled` run that threw |
| WARN | degraded, still serving | a real-time push that failed while the notification was stored; a valid signature naming an account that no longer exists |
| INFO | a state transition | the audit stream (ADR-011); a scheduled run's outcome and duration |
| DEBUG | for a developer reading along | domain-event publication; why a token was rejected; a 4xx rejection |

**Nothing personal may appear in a log line.** NFR-6 already said the application does not log personal
data; this states the operative rule for new statements: **log ids and enum values — never a title, an
email address, a reflection body, a progress note or an IP address.** ADR-011's `AuditEvent` has no
free-text field at all, so for the audit stream the type system enforces it rather than a reviewer.

## Consequences

**Easier.** A container's log is queryable by trace id, level, logger and service without parsing. A
line is now worth writing, because writing one is the only way anybody will ever know what the system
did. Turning DEBUG on for one run is an environment variable, not a deployment.

**Harder.** There are two output formats, so there are two ways to be wrong, and the local one is the
one nobody is looking at when it breaks. `LogOutputFormatTest` covers both for that reason. The file
also has to boot a real `SpringApplication` to read the shipped configuration the way Boot does, which
mutates JVM-global logging state; it restores the plain-text configuration when it finishes.

The dependency is unmanaged, so a Boot upgrade can silently pair logback 1.5 with an encoder built for
1.4. The failure would be at class-load, which is loud, but the pin needs a look on every upgrade.

**`INFO` by default means the domain-event DEBUG line disappears from a default run.** That line was
the only thing the scheduled jobs logged. ADR-011 and the scheduler statements replace it with
something that says more.

## Alternatives considered

- **Upgrade Spring Boot to 3.4+ and use `logging.structured.format.console`.** No extra dependency, no
  XML, and the right long-term answer. Rejected *for this change*: a minor-version Boot upgrade moves
  security, JPA, actuator and tracing auto-configuration at once, and ADR-007 depends on
  auto-configuration defaults by design. That is its own change with its own risk, and bundling it here
  would mean an observability regression and a framework upgrade in one unreviewable diff. *Revisit
  when* Boot is next upgraded — at which point most of `logback-spring.xml` is deleted rather than
  migrated. **The dependency is not**, and that cost should not be discovered during the upgrade:
  `LoggingAuditTrail`, both schedulers and `StompNotificationPushAdapter` import
  `StructuredArguments.keyValue`, and Boot's built-in structured logging has no equivalent — those four
  call sites need a replacement before the artifact can go.
- **JSON in every environment, including locally.** One format, one way to be wrong. Rejected because a
  developer reads the console directly and JSON is materially harder to scan; the cost is paid every
  day for a property that matters only where a machine reads the log.
- **Keeping the plain-text pattern and no structured logging at all.** Defensible on the grounds that
  trace ids already correlate lines and nothing ships logs anywhere. Rejected: the moment there is
  somewhere to ship them, every line already written is unstructured, and the standing guideline asks
  for structured output. *Revisit* is not offered — this is the cheap end of the decision.
- **A file appender with a rolling policy.** Rejected: the process runs in a container, `docker logs`
  is the sink, and a log file inside a container filesystem is a log nobody reads and a disk nobody
  watches. `.gitignore` already ignores `*.log` and `logs/`. *Revisit when* there is a deployment that
  is not a container.
