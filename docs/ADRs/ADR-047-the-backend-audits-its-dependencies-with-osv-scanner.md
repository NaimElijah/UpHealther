# ADR-047: The backend audits its dependencies with OSV-Scanner, and any known vulnerability fails the build

- **Status:** Accepted — since [#365](https://github.com/NaimElijah/UpHealther/pull/365), `backend/pom.xml`
  also overrides `opentelemetry.version`, from Boot's 1.62.0 to 1.67.0 for GHSA-ccrj-gw8r-854m
  ([#364](https://github.com/NaimElijah/UpHealther/issues/364)): five minors past what Boot manages, not a
  patch. So the "delete the overrides" trigger under *When to revisit* is per override, and
  OpenTelemetry's is that Boot manages 1.67.0 or newer, whatever Tomcat and Jackson do; it extends
  [ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md), adding `/scripts` to the
  `docker-compose` entry for the audit's OSV-Scanner image
- **Date:** 2026-10-19
- **Supersedes in part:** [ADR-002](ADR-002-close-the-gap-between-the-described-and-enforced-architecture.md)
  (its known gap, "The backend has no vulnerability audit yet")
- **Scope:** `scripts/compose.audit.yml`, the root `package.json`'s `audit:backend` and `test:backend`,
  the version overrides in `backend/pom.xml`, `ContainerImagesArePinnedTest`,
  `OsvScannerExceptionsAreDatedTest`, the backend job in
  `.github/workflows/ci.yml`, and `.github/dependabot.yml`'s `docker-compose` entry. NFR-12 in
  `docs/requirements/requirements.md`.
- **Issue:** [#59](https://github.com/NaimElijah/UpHealther/issues/59), part of the repository
  baseline epic [#197](https://github.com/NaimElijah/UpHealther/issues/197). It extends
  [ADR-044](ADR-044-one-root-package-json-runs-setup-start-and-test.md) and answers the image-scan
  question [ADR-045](ADR-045-ci-builds-and-starts-both-images.md) left for this issue.

## Context

The frontend has failed its build on a known vulnerability since ADR-002
(`npm audit --omit=dev --audit-level=high`). The backend audited nothing:

- **OWASP dependency-check** was added, run and removed, because it cannot fill its database without
  an `NVD_API_KEY`.
- ADR-002 judged a check that always fails, or one marked `continue-on-error`, worse than none.

#59 found scanners that need no key, but held off while the backend was on Spring Boot 3.2. A gate
against a tree that far out of support would only have said "upgrade". Spring Boot 4.1
([ADR-032](ADR-032-the-backend-runs-on-spring-boot-4-1.md)) removed that objection.

The first scan of the 4.1.1 tree found 17 known vulnerabilities in 5 packages, every one with a fix
released:

| Package | Version | Findings | Fixed in |
|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | 3 critical (CVSS 9.8, 9.1, 9.1) | 11.0.25 |
| `tools.jackson.core:jackson-databind`, `jackson-core` | 3.1.5 | 5 and 2, high and medium | 3.1.7 |
| `com.fasterxml.jackson.core:jackson-databind`, `jackson-core` (under jjwt) | 2.21.5 | the same seven advisories | 2.21.7 |

Spring Boot 4.1.1 is the newest release, so no Boot patch carries the fixes yet.

## Decision

### The tool and how it runs

**OSV-Scanner audits the backend.** It is Google's scanner over the OSV database: Apache-2.0,
released monthly (v2.7.0 on 2026-10-12), and it needs no account or key.

**It runs from its published image, through `scripts/compose.audit.yml`.** `npm run audit:backend`
runs `docker compose -f scripts/compose.audit.yml run --rm osv-scanner`. The service:

- mounts `backend/` read-only, and receives no token and no environment;
- scans `pom.xml` alone (`--lockfile /src/pom.xml`). A recursive scan would also read `target/`, and
  from the root it would audit the frontend's development tree, which ADR-044 excludes on purpose;
- uses the default bridge network (`network_mode: bridge`), so `run --rm` leaves no project network
  behind;
- needs Docker, which `test:backend` already needs for the integration tests.

Compose resolves `../backend` against the file, so the one script string runs under `cmd.exe` and
`sh` alike, as ADR-044 requires.

**`test:backend` runs it after `clean verify`**: `... clean verify && npm run audit:backend`. That
matches the frontend, whose audit follows its tests. npm's `> audit:backend` banner names the check
when it fails. CI's backend job runs `test:backend` as before, so its required check's name is
unchanged and ruleset 25310599 needs no edit.

**The image is held to an exact version like every other.** `ContainerImagesArePinnedTest` already
reads `scripts/compose.audit.yml`. OSV-Scanner tags its releases `v2.7.0`, so the test now accepts a
leading `v`: `v2.7.0` names an exact version as surely as `2.7.0` does. `v2`, `latest` and `stable`
are still refused. Dependabot's `docker-compose` entry reads `/scripts` too
([ADR-046](ADR-046-dependabot-proposes-weekly-updates-into-dev.md)), so the scanner should get bump
PRs:
- its file fetcher's pattern matches `compose.audit.yml`;
- the reviewer of #303 confirmed that the YAML loader, called the way Dependabot calls it, parses
  both files in `scripts/`, `!reset` included.

Whether its update checker handles a `v`-prefixed tag on ghcr.io is not verified until the first
runs on `main`, alongside ADR-046's own unknowns.

### What it scans

**The whole resolved tree, test scope included.** OSV-Scanner resolves `pom.xml` transitively. On
2026-10-19 it resolved the same 196 packages at the same versions as
`./mvnw dependency:list`:

| Scope | Packages |
|---|---|
| compile | 115 |
| runtime | 19 |
| test | 62 |

So it audits the build Maven actually runs, not a re-resolution that might disagree. Test scope
cannot be left out, which would mirror the frontend's `--omit=dev`:

- OSV-Scanner marks only the 10 *direct* test dependencies, the ones `pom.xml` declares with
  `<scope>test</scope>`, with the `test` group;
- their transitive tree, such as Testcontainers' `docker-java`, carries no group at all;
- so a `[[PackageOverrides]] group = "test"` rule would exclude 10 of Maven's 62 test-scope packages
  and keep the other 52.

Auditing it all is the stricter of the two honest options. The test tree also runs in CI, with the
Docker socket.

### The threshold

**Any known vulnerability fails the build.** OSV-Scanner has no severity threshold. Its exit codes,
checked against v2.7.0:

| Exit | Meaning |
|---|---|
| 0 | nothing found |
| 1 | a known vulnerability |
| 127 | a general error: the pom path does not resolve, or deps.dev, Maven Central or api.osv.dev cannot be reached |
| 128 | no packages found |

So a misconfigured or offline audit fails rather than passing empty, and:

- there is no equivalent of the frontend's `--audit-level=high`;
- accepting a finding is an `[[IgnoredVulns]]` entry in `backend/osv-scanner.toml`, with its `id`, a
  `reason` and an `ignoreUntil` date;
- every exception is a dated, reviewed decision rather than a blanket rule;
- no such file exists today, because nothing is accepted.

**The scanner does not enforce that rule, so a test does.** OSV-Scanner honours a bare `id`: it
filters the finding "because: (no reason given)", and with no `ignoreUntil` the finding never comes
back. A `[[PackageOverrides]]` entry can silence a whole package the same way.
`OsvScannerExceptionsAreDatedTest` fails the build on any `[[IgnoredVulns]]` without a non-blank
`reason` and an `ignoreUntil`, and on any `[[PackageOverrides]]` without a `reason` and an
`effectiveUntil`. It reads the file only when it exists.

### The day-one findings are fixed, not accepted

`backend/pom.xml` overrides three of Spring Boot's managed versions, each to the newest patch of the
line Boot 4.1.1 already uses:

| Property | Boot 4.1.1 manages | Override |
|---|---|---|
| `tomcat.version` | 11.0.24 | 11.0.27 |
| `jackson-bom.version` | 3.1.5 | 3.1.8 |
| `jackson-2-bom.version` | 2.21.5 | 2.21.8 |

**Image scanning stays out of this.** `osv-scanner scan image` would avoid the Trivy objection in
ADR-045. But nothing publishes an image yet, and #154 and #213 plan the registry's own scanning (ECR)
for the images that will. ADR-045's revisit trigger is unchanged.

## Consequences

- **A known vulnerability in any backend dependency now fails CI and `npm test`**, as one in the
  frontend's shipped dependencies already did.
- **A newly published advisory can turn an unrelated PR red** on the day it appears. This is the same
  property `npm audit` has. The fix is a bump, or a dated ignore with its reason.
- **The overrides go stale silently.** A later Spring Boot patch will manage newer Tomcat or Jackson
  versions, and an override left in place would hold them back. The audit catches an override only
  once it is vulnerable, not once it is merely old. The comment in `pom.xml` says to delete each
  override once the parent manages that version or newer. The Dependabot PR that moves
  `spring-boot-starter-parent` is where that gets checked.
- **Nothing proposes the overrides' next patch.** Dependabot reads a Maven version only from a
  dependency, plugin, extension or parent declaration, and these properties only override the
  parent's. That breaks NFR-53 for them, so NFR-53 carries a Known deviation against
  [#304](https://github.com/NaimElijah/UpHealther/issues/304), which deletes the overrides once Boot
  catches up.
  - Declaring each artifact in `<dependencyManagement>` would make Dependabot see them. But it would
    also keep them pinned past the point Boot manages them, which is the opposite of what an override
    meant to be temporary needs.
- **The backend's rule is stricter than the frontend's.** Any finding fails, not only high and
  critical, and the test tree is included. A finding nobody can act on costs one dated ignore.
- **The audit needs the network.** It resolves through deps.dev, reads the parent poms from Maven
  Central, and queries api.osv.dev. Blocking any one of them fails the run with exit 127, so an
  outage reddens the backend job rather than letting it pass unaudited.
- **It adds under a minute to the backend's run.** About 55 s locally with the image cached, most of
  it resolution. About 46 s in CI on #59's PR, 9 s of it pulling the image (run 39314189272).
- **ADR-002's known gap is closed**, and its "Revisit when an `NVD_API_KEY` secret exists" no longer
  applies.

## Alternatives considered

- **OWASP dependency-check.** Rejected again. It still needs an `NVD_API_KEY`, a secret this repository
  would have to keep, for a result OSV-Scanner gives without one.
- **Trivy (`fs` scan).** Rejected. It has a real severity threshold, but its distribution was
  compromised on 2026-03-19 (GHSA-69fq-xp46-6x23, recorded in ADR-045).
- **Sonatype OSS Index** (`ossindex-maven-plugin`). Rejected. It has required authentication since
  September 2025, now through a Sonatype Guide personal access token.
- **`actions/dependency-review-action`.** Rejected as the gate. It fails a PR that *introduces* a
  vulnerable dependency, but:
  - it says nothing about an advisory published against a dependency already in the tree;
  - it runs only in GitHub Actions, so `npm test` could not apply it (ADR-044).
- **Dependabot alerts alone** (ADR-046). Rejected as the gate, because an alert never fails a build.
  The two complement each other: Dependabot proposes the fix, the audit refuses to merge without it.
- **A severity threshold computed from OSV-Scanner's JSON.** Rejected. It means a script of our own
  to parse and rank severities. Not every OSV record carries a CVSS score, so the script would also
  need a rule for the ones that do not.
  - **Revisit when** OSV-Scanner gains a threshold flag, or `[[IgnoredVulns]]` fills with low and
    moderate findings nobody intends to fix.
- **Audit only what ships, through a CycloneDX SBOM** (`cyclonedx-maven-plugin` leaves test scope out
  by default, and OSV-Scanner reads the SBOM). Rejected for now. It adds a Maven plugin, and it ties
  the audit to a build having run first.
  - **Revisit when** a test-only advisory reddens a PR that cannot act on it.
- **The OSV-Scanner binary, downloaded by a `scripts/*.mjs`.** Rejected. It means six platform
  checksums kept by hand, and no updater reads them. The image is pinned in a file Dependabot keeps
  current.
- **Google's `osv-scanner-action` reusable workflow.** Rejected. It runs only in CI, so local runs and
  CI would apply different checks.

## When to revisit

- **OSV-Scanner adds a severity threshold:** consider matching the frontend's.
- **A test-only advisory reddens a PR:** the SBOM alternative above.
- **An image is published** (#154): scanning, together with ADR-045.
- **Spring Boot manages Tomcat ≥ 11.0.27, Jackson 3 ≥ 3.1.8 and Jackson 2 ≥ 2.21.8:** delete the
  overrides.
