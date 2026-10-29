# ADR-057: The developer stack is reachable from this machine only, and gains no privilege

- **Status:** Accepted
- **Date:** 2026-10-29
- **Scope:** `docker-compose.yml`'s `ports`, `security_opt`, `cap_drop` and PostgreSQL's health-check;
  `ComposeStackHardeningTest`; the README's start instructions. It leaves `scripts/compose.images-check.yml`
  as [ADR-045](ADR-045-ci-builds-and-starts-both-images.md) and
  [ADR-052](ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md) made it.
- **Issue:** [#336](https://github.com/NaimElijah/UpHealther/issues/336), scan 02 of
  [#334](https://github.com/NaimElijah/UpHealther/issues/334), findings I2.1, I2.7 and I2.8.
- **Relates to:** [ADR-041](ADR-041-the-refresh-cookie-is-always-secure.md), whose "a phone on the LAN"
  revisit trigger this rules out for the compose stack, and
  [ADR-042](ADR-042-the-jar-carries-no-jwt-signing-key.md), whose published development key compose
  supplies.

## Context

`docker-compose.yml` is the local stack (`npm start`), and its defaults are published on purpose, so that
a clone runs with no setup:
- PostgreSQL's superuser password `healthupgrades`;
- the development signing key ADR-042 keeps out of the jar.

Scan 02 found three things about how it ran:

- **Every port was published on every interface:** `"5432:5432"`, `"8080:8080"` and `"3000:80"`. Anyone
  sharing the network could open the database as its superuser, or mint a token for any account with
  the published key. On Linux, Docker's own iptables rules bypass a host firewall such as ufw. The image
  check had bound loopback since ADR-052; the developer's own stack had not.
- **The database's health-check asked over the Unix socket.** On an empty volume the image first runs a
  temporary server that listens on the socket only. Polled on `postgres:15.19-alpine3.24`, the socket
  answered "accepting connections" while TCP had no response, so the backend could start against a
  database not yet listening on 5432.
- **No service narrowed its privileges.** The backend runs as root until #213, with Docker's default
  capability set, and any setuid binary could raise it further.

## Decision

**The developer stack publishes on `127.0.0.1` only, probes PostgreSQL over TCP, and no service can gain
a privilege. The backend drops every capability. PostgreSQL and the frontend keep Docker's default set.**

- **Ports:** `127.0.0.1:5432`, `127.0.0.1:8080` and `127.0.0.1:3000`. `http://localhost:3000` still works
  from this machine. Docker publishes no `::1` binding, which answers with a refusal, so a client falls
  back to IPv4 at once. That was checked with curl, Chromium, Chrome, Edge and Firefox against a
  loopback-only port on Windows.
- **Health-check:** `pg_isready -h 127.0.0.1`, which stays unready through the temporary server.
- **`no-new-privileges:true`** on all three services.
- **`cap_drop: [ALL]`** on the backend. The JVM binds 8080 and needs no capability.
- **Not narrowed, on purpose:**
  - **PostgreSQL** keeps its set. Its entrypoint runs as root only to initialise the data directory.
    It then hands the server to uid 70, which holds no effective capability (`CapEff 0`, checked under
    `no-new-privileges`). So a list here would constrain that short-lived step and nothing that serves
    a connection.
  - **The frontend** keeps its set until #213 makes nginx unprivileged and it can drop them all. Its
    root master needs only about four, but a list here would have to track the image's entrypoint
    meanwhile.
- **Enforced by `ComposeStackHardeningTest`,** a no-Docker contract test in `./mvnw test`. It requires
  every published port to be on `127.0.0.1` and fails if it finds none. It also requires the TCP probe,
  `no-new-privileges` on every service, and the backend's `cap_drop`. The image check cannot see the
  base file's ports, because its override resets them, so it guards none of this.

## Consequences

- **Nobody else on the network reaches a developer's database or API through compose.**
  - **On Docker Desktop for Windows,** the published port's only listener is `com.docker.backend` on
    `127.0.0.1`, checked with `Get-NetTCPConnection`. Docker Desktop for macOS was not checked.
  - **On a Linux Docker Engine, from 28.3.3.** Before 28.0.0, a neighbouring host could reach a
    loopback-published port. Before 28.3.3, a firewalld reload exposed it again (CVE-2025-54388). The
    README names the version.
- **A phone or a second machine cannot test the compose stack.** That is ADR-041's revisit case, and it
  was already only half-working: an insecure origin gets no refresh cookie, so a session lasts one
  access token.
- **The image check and the developer stack now bind the same address.** The override still restates
  `127.0.0.1:3000`, so the check stays on loopback whatever this file says.
- **A setuid binary cannot raise any process's privileges,** in any of the three containers.
- **This covers compose only.** A native `./mvnw spring-boot:run` still listens on every interface with
  the published key. That is #366.

## Alternatives considered

- **A `BIND_ADDRESS` variable, defaulting to `127.0.0.1`,** so a developer could open the stack to the
  LAN. Rejected for now. Nothing needs it today, and it would make the unsafe setting one `.env` line
  away, beside a published superuser password.
  - **Revisit:** when local development needs another device, per ADR-041's trigger. Take it then with
    that ADR's cookie question.
- **Minimal `cap_add` lists for PostgreSQL and nginx.** Rejected. PostgreSQL's server already runs with
  none, and nginx's list is about to change under #213. Each list would track an image's entrypoint
  rather than anything this repository decides.
- **Rely on the image check to catch a regression.** Rejected: its override resets the ports it would
  have to see.

## When to revisit

- **#213 makes nginx unprivileged:** drop the frontend's capabilities too, and extend the test.
- **Local development needs another device on the network:** the alternative above, with ADR-041.
- **The compose stack is ever used beyond one machine:** it was not built for that, and its published
  defaults would have to go first.
