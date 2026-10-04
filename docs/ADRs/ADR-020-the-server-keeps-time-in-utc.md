# ADR-020: The server keeps time in UTC

- **Status:** Accepted
- **Date:** 2026-10-04
- **Scope:** the `Clock` bean in `HealthUpgradesApplication`, `common/time/ServerZone`, and the `zone`
  of every `@Scheduled` cron. No new dependency, and no new configuration.
- **Issue:** [#100](https://github.com/NaimElijah/UpHealther/issues/100)

## Context

NFR-15 says time-dependent behaviour reads an injected clock, *so it is testable and timezone-explicit*.
The services honoured the first half. Nothing honoured the second:

- The clock was `Clock.systemDefaultZone()`, the host's zone.
- The four crons named no zone, so Spring resolved each one in the host's zone too.
- `application.yml` said they ran in "server timezone", which no line of code chose.

So the zone was an accident of wherever the process ran:

- The runtime image (`eclipse-temurin:21-jre-alpine`) has no `TZ` and no `/etc/localtime`, so its JVM
  defaults to `GMT`. That was checked by running a probe in the image.
- `mvn spring-boot:run` on a developer's machine uses that machine's zone.

The same build therefore decided "today", fired the 08:00 overdue scan and matched a 09:00 reminder
at different instants depending on the host.

**A user has no zone.** A reminder stores a wall-clock time, and the scheduler compares it with the
clock's time. Whatever zone the server picks is therefore the zone every reminder fires in, for every
user. No server-side choice makes reminders local to users in different places.

## Decision

**The server keeps time in UTC, fixed in code.**

- `ServerZone.ID` is `"UTC"`. It is a compile-time constant, so an annotation can name it.
- The `Clock` bean is `Clock.system(ServerZone.ZONE)`.
- Every `@Scheduled` cron declares `zone = ServerZone.ID`, so a cron and the clock it consults always
  agree.

`HealthUpgradesApplicationTest` pins the clock. It gives the host a non-UTC zone, because CI's host
is UTC already and would not notice a regression. `ServerTimeArchitectureTest` fails the build on a
`@Scheduled` method that does not name the zone. A fifth scheduler added without it is exactly how
the two would drift apart again.

## Consequences

**What this makes easy.**

- The zone is chosen once, in code, and read in one place.
- A native run behaves like the container. The overdue scan, the check-in nudge and the reminder
  minute fire at the same instants on every host.
- There is nothing to configure, so nothing can be forgotten. That is the failure #87 describes for
  `AUTH_COOKIE_SECURE`.

**What this makes hard.**

- **Every user lives on UTC's day and clock.** A 09:00 reminder rings at 09:00 UTC wherever its
  owner is. "Today", the seven-day window and where a streak starts follow UTC's midnight.
  `architecture.md` describes how that meets the browser's own "today".
  - This is what the container already did, so making it explicit changes nothing for users there.
- **Changing the zone is a code change.** This is deliberate; see the second alternative.

**Entity timestamps, except a notification's, are not covered here.** `createdAt` and `updatedAt`
are still stamped in `@PrePersist`/`@PreUpdate` from the JVM's default zone, not from this clock. That
is #51's item 8, and NFR-15 keeps a deviation for it.
- In the container the two agree, because the JVM default is `GMT`.
- On a non-UTC developer machine they differ by the host's offset.

**A notification is the exception, because one is compared with the clock.** The daily check-in skips
a user already nudged since the clock's midnight. With the clock on UTC and the stamp still in the
host's zone, a nudge sent at 18:00 UTC was stored after local midnight on any host east of UTC+6, and
the next day's run skipped the user. So `NotificationService.create` stamps `createdAt` from this
clock, which `Notification.onCreate` keeps. No other entity timestamp is compared with a value the
clock produced.

## Alternatives considered

**A per-user zone.** This is the only design that makes reminders and "today" local to each user. It
was rejected here because it is a feature, not a fix:

- a zone column on `users` and a way to set it;
- reminders evaluated per user;
- a per-user notion of today threaded through tracking and the dashboard.

Revisit when local-time reminders are asked for. That would supersede this record's consequences
rather than its decision, since the server still needs one zone for its own jobs.

**A configurable server zone** (`APP_TIME_ZONE`, defaulting to UTC). It would feed the clock and every
cron. It was rejected because a server-wide setting can only move every user to one other zone. For a
user base in more than one zone it is no better than UTC, and it adds a variable, its documentation
and a way to misconfigure a deployment.
- **Revisit when** a deployment serves users in a single region outside UTC *and* per-user zones are
  not yet built. Then a server-wide zone is a real improvement, and the constant becomes a property.

**Keep the host's zone.** This was rejected because it is what NFR-15's "timezone-explicit" rules out.
The zone would be a property of the machine rather than of the system.

**Pin the JVM default instead**, with `TimeZone.setDefault` in `main` or `-Duser.timezone=UTC` in the
image. That would also move the entity timestamps onto UTC. It was rejected for three reasons:

- It is global state that every library in the process reads.
- It is invisible at the call site.
- It leaves the clock reading "the default" rather than a zone the code names.

#51 is the right fix for the entity timestamps: route them through the clock, which a JVM flag would
only paper over.
